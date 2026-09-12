package com.blindway.perception.application;

import com.blindway.perception.domain.MqttEnvelope;
import com.blindway.perception.infrastructure.MqttProperties;
import com.blindway.perception.infrastructure.PerceptionMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MqttIngressService {

    static final Pattern TOPIC =
            Pattern.compile("^blindway/v1/devices/([0-9a-fA-F-]{36})/(heartbeat|path-events|obstacle-events)$");

    private final ObjectMapper objectMapper;
    private final PerceptionMapper mapper;
    private final MqttProperties properties;
    private final Counter accepted;
    private final Counter rejected;
    private final Counter duplicates;
    private final Timer persistenceTimer;

    public MqttIngressService(
            ObjectMapper objectMapper,
            PerceptionMapper mapper,
            MqttProperties properties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.mapper = mapper;
        this.properties = properties;
        this.accepted = meterRegistry.counter("blindway.mqtt.messages", "result", "accepted");
        this.rejected = meterRegistry.counter("blindway.mqtt.messages", "result", "rejected");
        this.duplicates = meterRegistry.counter("blindway.mqtt.messages", "result", "duplicate");
        this.persistenceTimer = meterRegistry.timer("blindway.mqtt.inbox.persist");
    }

    @Transactional
    public Result accept(String topic, byte[] bytes) {
        return accept(topic, bytes, Instant.now());
    }

    @Transactional
    public Result accept(String topic, byte[] bytes, Instant receivedAt) {
        if (bytes.length > properties.maxPayloadBytes()) {
            rejected.increment();
            return Result.REJECTED;
        }
        Matcher topicMatcher = TOPIC.matcher(topic);
        if (!topicMatcher.matches()) {
            rejected.increment();
            return Result.REJECTED;
        }

        String rawPayload = new String(bytes, StandardCharsets.UTF_8);
        MqttEnvelope envelope;
        try {
            envelope = objectMapper.readValue(rawPayload, MqttEnvelope.class);
            validateEnvelope(envelope, UUID.fromString(topicMatcher.group(1)));
        } catch (JacksonException | IllegalArgumentException exception) {
            rejected.increment();
            return Result.REJECTED;
        }

        int inserted = persistenceTimer.record(() -> mapper.insertInbox(envelope, topic, receivedAt, rawPayload));
        if (inserted == 0) {
            duplicates.increment();
            return Result.DUPLICATE;
        }
        accepted.increment();
        return Result.ACCEPTED;
    }

    static void validateEnvelope(MqttEnvelope envelope, UUID topicDeviceId) {
        if (envelope == null
                || !"1.0".equals(envelope.schemaVersion())
                || envelope.eventId() == null
                || envelope.deviceId() == null
                || envelope.bootId() == null
                || envelope.sequenceNo() == null
                || envelope.sequenceNo() < 1
                || envelope.occurredAt() == null
                || envelope.sentAt() == null
                || envelope.payload() == null
                || !topicDeviceId.equals(envelope.deviceId())
                || envelope.sentAt().isBefore(envelope.occurredAt())) {
            throw new IllegalArgumentException("INVALID_ENVELOPE");
        }
    }

    public enum Result {
        ACCEPTED,
        DUPLICATE,
        REJECTED
    }
}

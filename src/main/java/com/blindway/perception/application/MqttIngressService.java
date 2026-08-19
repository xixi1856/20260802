package com.blindway.perception.application;

import com.blindway.device.DeviceAccess;
import com.blindway.perception.domain.HeartbeatPayload;
import com.blindway.perception.domain.MqttEnvelope;
import com.blindway.perception.domain.ObstacleEventPayload;
import com.blindway.perception.domain.PathEventPayload;
import com.blindway.perception.infrastructure.MqttProperties;
import com.blindway.perception.infrastructure.PerceptionMapper;
import com.blindway.trip.TripLocationAccess;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MqttIngressService {

    private static final Logger log = LoggerFactory.getLogger(MqttIngressService.class);
    private static final Pattern TOPIC =
            Pattern.compile("^blindway/v1/devices/([0-9a-fA-F-]{36})/(heartbeat|path-events|obstacle-events)$");

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final DeviceAccess devices;
    private final TripLocationAccess trips;
    private final PerceptionMapper mapper;
    private final MqttProperties properties;
    private final Counter rejected;
    private final Counter duplicates;
    private final Timer persistenceTimer;

    public MqttIngressService(
            ObjectMapper objectMapper,
            Validator validator,
            DeviceAccess devices,
            TripLocationAccess trips,
            PerceptionMapper mapper,
            MqttProperties properties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.devices = devices;
        this.trips = trips;
        this.mapper = mapper;
        this.properties = properties;
        this.rejected = meterRegistry.counter("blindway.mqtt.messages", "result", "rejected");
        this.duplicates = meterRegistry.counter("blindway.mqtt.messages", "result", "duplicate");
        this.persistenceTimer = meterRegistry.timer("blindway.mqtt.persistence");
    }

    @Transactional
    public Result accept(String topic, byte[] bytes) {
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
        } catch (RuntimeException exception) {
            rejected.increment();
            return Result.REJECTED;
        }

        Instant receivedAt = Instant.now();
        if (mapper.insertInbox(envelope, topic, receivedAt, rawPayload) == 0) {
            duplicates.increment();
            return Result.DUPLICATE;
        }

        MDC.put("eventId", envelope.eventId().toString());
        MDC.put("deviceId", envelope.deviceId().toString());
        try {
            persistenceTimer.record(() -> process(topicMatcher.group(2), envelope, receivedAt));
            mapper.updateInbox(envelope.eventId(), "PROCESSED", null, envelope.tripId(), Instant.now());
            return Result.PROCESSED;
        } catch (RejectedEventException exception) {
            rejected.increment();
            mapper.updateInbox(envelope.eventId(), "REJECTED", exception.code, envelope.tripId(), Instant.now());
            log.warn("Rejected MQTT event: {}", exception.code);
            return Result.REJECTED;
        } finally {
            MDC.remove("eventId");
            MDC.remove("deviceId");
        }
    }

    private void process(String type, MqttEnvelope envelope, Instant receivedAt) {
        if (!devices.existsAndEnabled(envelope.deviceId())) {
            throw new RejectedEventException("DEVICE_NOT_FOUND_OR_DISABLED");
        }
        switch (type) {
            case "heartbeat" -> processHeartbeat(envelope, receivedAt);
            case "path-events" -> processPath(envelope, receivedAt);
            case "obstacle-events" -> processObstacle(envelope, receivedAt);
            default -> throw new RejectedEventException("TOPIC_NOT_SUPPORTED");
        }
    }

    private void processHeartbeat(MqttEnvelope envelope, Instant receivedAt) {
        HeartbeatPayload payload = convertAndValidate(envelope, HeartbeatPayload.class);
        mapper.insertHeartbeat(envelope, payload, receivedAt);
        devices.markSeen(envelope.deviceId(), envelope.occurredAt(), payload.softwareVersion(), payload.modelVersion());
    }

    private void processPath(MqttEnvelope envelope, Instant receivedAt) {
        PathEventPayload payload = convertAndValidate(envelope, PathEventPayload.class);
        TripLocationAccess.LocationMatch match =
                trips.resolve(envelope.deviceId(), envelope.tripId(), envelope.occurredAt());
        mapper.insertPathObservation(envelope, payload, match, receivedAt);
        mapper.updateInbox(envelope.eventId(), "RECEIVED", null, match.tripId(), Instant.now());
    }

    private void processObstacle(MqttEnvelope envelope, Instant receivedAt) {
        ObstacleEventPayload payload = convertAndValidate(envelope, ObstacleEventPayload.class);
        if (payload.minimumDistanceMeters() > payload.warningThresholdMeters()
                || payload.obstacles().stream()
                        .anyMatch(value -> value.distanceMeters() > payload.warningThresholdMeters())) {
            throw new RejectedEventException("OBSTACLE_OUTSIDE_WARNING_THRESHOLD");
        }
        TripLocationAccess.LocationMatch match =
                trips.resolve(envelope.deviceId(), envelope.tripId(), envelope.occurredAt());
        String obstaclesJson;
        try {
            obstaclesJson = objectMapper.writeValueAsString(payload.obstacles());
        } catch (JacksonException exception) {
            throw new RejectedEventException("OBSTACLE_SERIALIZATION_FAILED");
        }
        mapper.insertObstacleEvent(envelope, payload, obstaclesJson, match, receivedAt);
        mapper.updateInbox(envelope.eventId(), "RECEIVED", null, match.tripId(), Instant.now());
    }

    private void validateEnvelope(MqttEnvelope envelope, UUID topicDeviceId) {
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
            throw new RejectedEventException("INVALID_ENVELOPE");
        }
    }

    private <T> T convertAndValidate(MqttEnvelope envelope, Class<T> type) {
        final T payload;
        try {
            payload = objectMapper.treeToValue(envelope.payload(), type);
        } catch (JacksonException exception) {
            throw new RejectedEventException("INVALID_PAYLOAD");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            throw new RejectedEventException("INVALID_PAYLOAD");
        }
        return payload;
    }

    public enum Result {
        PROCESSED,
        DUPLICATE,
        REJECTED
    }

    private static final class RejectedEventException extends RuntimeException {
        private final String code;

        private RejectedEventException(String code) {
            this.code = code;
        }
    }
}

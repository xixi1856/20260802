package com.blindway.perception.application;

import com.blindway.common.EventTopics;
import com.blindway.common.IntegrationEventOutbox;
import com.blindway.device.DeviceAccess;
import com.blindway.perception.PerceptionRecordedEvent;
import com.blindway.perception.domain.HeartbeatPayload;
import com.blindway.perception.domain.MqttEnvelope;
import com.blindway.perception.domain.ObstacleEventPayload;
import com.blindway.perception.domain.PathEventPayload;
import com.blindway.perception.infrastructure.MqttInboxRow;
import com.blindway.perception.infrastructure.PerceptionMapper;
import com.blindway.trip.TripLocationAccess;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class MqttInboxProcessor {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final DeviceAccess devices;
    private final TripLocationAccess trips;
    private final PerceptionMapper mapper;
    private final IntegrationEventOutbox outbox;
    private final Counter processed;
    private final Counter rejected;
    private final Timer processingTimer;

    public MqttInboxProcessor(
            ObjectMapper objectMapper,
            Validator validator,
            DeviceAccess devices,
            TripLocationAccess trips,
            PerceptionMapper mapper,
            IntegrationEventOutbox outbox,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.devices = devices;
        this.trips = trips;
        this.mapper = mapper;
        this.outbox = outbox;
        this.processed = meterRegistry.counter("blindway.mqtt.inbox", "result", "processed");
        this.rejected = meterRegistry.counter("blindway.mqtt.inbox", "result", "rejected");
        this.processingTimer = meterRegistry.timer("blindway.mqtt.inbox.process");
    }

    @Transactional
    public Outcome process(MqttInboxRow row, String owner) {
        MqttEnvelope envelope = decode(row);
        MDC.put("eventId", envelope.eventId().toString());
        MDC.put("deviceId", envelope.deviceId().toString());
        try {
            UUID matchedTripId = processingTimer.record(() -> processBusiness(row, envelope));
            requireOwnedLease(mapper.markProcessed(row.eventId(), owner, Instant.now(), matchedTripId));
            processed.increment();
            return Outcome.PROCESSED;
        } catch (RejectedEventException exception) {
            requireOwnedLease(mapper.markRejected(row.eventId(), owner, exception.code, Instant.now()));
            rejected.increment();
            return Outcome.REJECTED;
        } finally {
            MDC.remove("eventId");
            MDC.remove("deviceId");
        }
    }

    private MqttEnvelope decode(MqttInboxRow row) {
        try {
            MqttEnvelope envelope = objectMapper.readValue(row.rawPayload(), MqttEnvelope.class);
            Matcher matcher = MqttIngressService.TOPIC.matcher(row.topic());
            if (!matcher.matches()) {
                throw new RejectedEventException("TOPIC_NOT_SUPPORTED");
            }
            MqttIngressService.validateEnvelope(envelope, UUID.fromString(matcher.group(1)));
            return envelope;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new RejectedEventException("INVALID_ENVELOPE");
        }
    }

    private UUID processBusiness(MqttInboxRow row, MqttEnvelope envelope) {
        if (!devices.existsAndEnabled(envelope.deviceId())) {
            throw new RejectedEventException("DEVICE_NOT_FOUND_OR_DISABLED");
        }
        String type = row.topic().substring(row.topic().lastIndexOf('/') + 1);
        return switch (type) {
            case "heartbeat" -> processHeartbeat(envelope, row.receivedAt());
            case "path-events" -> processPath(envelope, row.receivedAt());
            case "obstacle-events" -> processObstacle(envelope, row.receivedAt());
            default -> throw new RejectedEventException("TOPIC_NOT_SUPPORTED");
        };
    }

    private UUID processHeartbeat(MqttEnvelope envelope, Instant receivedAt) {
        HeartbeatPayload payload = convertAndValidate(envelope, HeartbeatPayload.class);
        mapper.insertHeartbeat(envelope, payload, receivedAt);
        devices.markSeen(envelope.deviceId(), envelope.occurredAt(), payload.softwareVersion(), payload.modelVersion());
        appendEvent(
                envelope,
                receivedAt,
                null,
                null,
                List.of(),
                TripLocationAccess.LocationMatch.unmatched(null, "HEARTBEAT"));
        return null;
    }

    private UUID processPath(MqttEnvelope envelope, Instant receivedAt) {
        PathEventPayload payload = convertAndValidate(envelope, PathEventPayload.class);
        TripLocationAccess.LocationMatch match =
                trips.resolve(envelope.deviceId(), envelope.tripId(), envelope.occurredAt());
        mapper.insertPathObservation(envelope, payload, match, receivedAt);
        appendEvent(envelope, receivedAt, payload.state().name(), null, List.of(), match);
        return match.tripId();
    }

    private UUID processObstacle(MqttEnvelope envelope, Instant receivedAt) {
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
        appendEvent(
                envelope,
                receivedAt,
                null,
                "OBSTACLE",
                payload.obstacles().stream()
                        .map(value -> value.category().name())
                        .distinct()
                        .toList(),
                match);
        return match.tripId();
    }

    private void appendEvent(
            MqttEnvelope envelope,
            Instant receivedAt,
            String pathState,
            String explicitType,
            List<String> obstacleCategories,
            TripLocationAccess.LocationMatch match) {
        String eventType = explicitType != null ? explicitType : pathState != null ? "PATH" : "HEARTBEAT";
        PerceptionRecordedEvent event = new PerceptionRecordedEvent(
                envelope.eventId(),
                eventType,
                envelope.deviceId(),
                match.tripId(),
                envelope.occurredAt(),
                receivedAt,
                match.quality(),
                match.longitude(),
                match.latitude(),
                pathState,
                obstacleCategories);
        outbox.append(
                envelope.eventId(),
                "DEVICE",
                envelope.deviceId(),
                "PERCEPTION_RECORDED",
                EventTopics.PERCEPTION_RECORDED,
                envelope.deviceId().toString(),
                event);
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

    private void requireOwnedLease(int updated) {
        if (updated != 1) {
            throw new LeaseLostException();
        }
    }

    public enum Outcome {
        PROCESSED,
        REJECTED
    }

    private static final class RejectedEventException extends RuntimeException {
        private final String code;

        private RejectedEventException(String code) {
            this.code = code;
        }
    }

    private static final class LeaseLostException extends RuntimeException {
        private LeaseLostException() {
            super("MQTT Inbox lease was lost before transaction completion");
        }
    }
}

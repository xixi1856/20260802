package com.blindway.perception.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record MqttInboxRow(UUID eventId, String topic, String rawPayload, Instant receivedAt, int attemptCount) {}

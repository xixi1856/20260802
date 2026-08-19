package com.blindway.perception.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record MqttEnvelope(
        String schemaVersion,
        UUID eventId,
        UUID deviceId,
        UUID tripId,
        UUID bootId,
        Long sequenceNo,
        Instant occurredAt,
        Instant sentAt,
        JsonNode payload) {}

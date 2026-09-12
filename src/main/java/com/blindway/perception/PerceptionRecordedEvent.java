package com.blindway.perception;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PerceptionRecordedEvent(
        UUID eventId,
        String eventType,
        UUID deviceId,
        UUID tripId,
        Instant occurredAt,
        Instant receivedAt,
        String locationQuality,
        Double longitude,
        Double latitude,
        String pathState,
        List<String> obstacleCategories) {}

package com.blindway.trip.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record TripRow(
        UUID id,
        UUID userId,
        UUID deviceId,
        String status,
        Instant startedAt,
        Instant endedAt,
        Instant createdAt,
        Instant updatedAt) {}

package com.blindway.trip.infrastructure;

import java.time.Instant;

public record TrackPointRow(
        long id,
        Instant recordedAt,
        double longitude,
        double latitude,
        double accuracyMeters,
        Double speedMetersPerSecond) {}

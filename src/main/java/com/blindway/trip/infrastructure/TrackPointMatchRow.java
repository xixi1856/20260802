package com.blindway.trip.infrastructure;

import java.time.Instant;

public record TrackPointMatchRow(
        Long id, Double longitude, Double latitude, Instant recordedAt, double accuracyMeters) {}

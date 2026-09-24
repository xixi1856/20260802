package com.blindway.trip.api;

import java.time.Instant;
import java.util.List;

public record TrackPointPageResponse(List<TrackPoint> points, Instant nextAfter) {

    public record TrackPoint(
            long id,
            Instant recordedAt,
            double longitude,
            double latitude,
            double accuracyMeters,
            Double speedMetersPerSecond) {}
}

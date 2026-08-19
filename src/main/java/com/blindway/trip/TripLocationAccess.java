package com.blindway.trip;

import java.time.Instant;
import java.util.UUID;

public interface TripLocationAccess {

    LocationMatch resolve(UUID deviceId, UUID requestedTripId, Instant occurredAt);

    record LocationMatch(
            UUID tripId,
            Long trackPointId,
            Double longitude,
            Double latitude,
            String status,
            String quality,
            Long timeOffsetMs) {

        public static LocationMatch unmatched(UUID tripId, String status) {
            return new LocationMatch(tripId, null, null, null, status, "UNMATCHED", null);
        }
    }
}

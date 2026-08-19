package com.blindway.trip.api;

import java.time.Instant;
import java.util.UUID;

public record TripResponse(UUID id, UUID deviceId, String status, Instant startedAt, Instant endedAt) {}

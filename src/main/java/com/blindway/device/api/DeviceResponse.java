package com.blindway.device.api;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(
        UUID id, String label, String status, boolean online, Instant lastSeenAt, Instant createdAt) {}

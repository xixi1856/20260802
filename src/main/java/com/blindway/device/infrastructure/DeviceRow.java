package com.blindway.device.infrastructure;

import com.blindway.device.domain.DeviceStatus;
import java.time.Instant;
import java.util.UUID;

public record DeviceRow(
        UUID id,
        UUID ownerUserId,
        String label,
        String secretHash,
        DeviceStatus status,
        Instant lastSeenAt,
        String softwareVersion,
        String modelVersion,
        Instant createdAt,
        Instant updatedAt) {}

package com.blindway.device;

import java.time.Instant;
import java.util.UUID;

public interface DeviceAccess {

    boolean existsAndEnabled(UUID deviceId);

    void markSeen(UUID deviceId, Instant occurredAt, String softwareVersion, String modelVersion);
}

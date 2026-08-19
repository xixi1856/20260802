package com.blindway.device.api;

import java.time.Instant;
import java.util.UUID;

public record RotatedDeviceSecretResponse(UUID deviceId, String deviceSecret, Instant rotatedAt) {}

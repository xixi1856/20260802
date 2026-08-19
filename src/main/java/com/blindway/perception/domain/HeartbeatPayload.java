package com.blindway.perception.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record HeartbeatPayload(
        @NotBlank String softwareVersion,
        @NotBlank String modelVersion,
        String configVersion,
        @NotNull @PositiveOrZero Long uptimeSeconds,
        @DecimalMin("-40") @DecimalMax("125") Double cpuTemperatureC,
        @NotNull @DecimalMin("0") @DecimalMax("100") Double cpuUsagePercent,
        @NotNull @DecimalMin("0") @DecimalMax("100") Double memoryUsagePercent,
        @NotNull @DecimalMin("0") @DecimalMax("100") Double diskUsagePercent,
        @PositiveOrZero Double inferenceFps,
        @PositiveOrZero Integer averageInferenceLatencyMs,
        NetworkType networkType,
        Integer signalDbm,
        @NotNull ComponentStatus cameraStatus,
        @NotNull ComponentStatus stereoStatus,
        @NotNull ComponentStatus inferenceStatus,
        @NotNull Boolean clockSynchronized,
        @PositiveOrZero Integer pendingEventCount,
        @PositiveOrZero Integer droppedEventCount) {

    public enum ComponentStatus {
        UP,
        DEGRADED,
        DOWN
    }

    public enum NetworkType {
        WIFI,
        CELLULAR,
        ETHERNET,
        UNKNOWN
    }
}

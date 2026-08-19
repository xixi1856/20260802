package com.blindway.perception.domain;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record PathEventPayload(
        @NotNull PathState state,
        @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence,
        @Positive Double nearestDistanceMeters,
        Double lateralOffsetMeters,
        @NotNull MeasurementQuality measurementQuality,
        @NotNull @PositiveOrZero Integer processingLatencyMs) {

    public enum PathState {
        LEFT,
        CENTER,
        RIGHT,
        NOT_DETECTED
    }
}

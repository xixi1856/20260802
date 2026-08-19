package com.blindway.perception.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ObstacleEventPayload(
        @NotNull @DecimalMin("0") @DecimalMax("1") Double tactileCorridorConfidence,
        @NotNull @Positive @DecimalMax("10") Double warningThresholdMeters,
        @NotNull @Positive @DecimalMax("10") Double minimumDistanceMeters,
        @NotNull @PositiveOrZero Integer processingLatencyMs,
        @NotEmpty @Size(max = 20) List<@Valid Obstacle> obstacles) {

    public record Obstacle(
            @NotNull Category category,
            @NotNull @DecimalMin("0") @DecimalMax("1") Double confidence,
            @NotNull @Positive @DecimalMax("10") Double distanceMeters,
            @NotNull MeasurementQuality distanceQuality,
            @NotNull RelativePosition relativePosition,
            @NotNull @Valid BoundingBox boundingBox) {}

    public record BoundingBox(
            @NotNull @DecimalMin("0") @DecimalMax("1") Double x,
            @NotNull @DecimalMin("0") @DecimalMax("1") Double y,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double width,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double height) {}

    public enum Category {
        PERSON,
        VEHICLE,
        TWO_WHEELER,
        STATIC_OBJECT,
        UNKNOWN
    }

    public enum RelativePosition {
        LEFT,
        CENTER,
        RIGHT
    }
}

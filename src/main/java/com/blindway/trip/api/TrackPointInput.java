package com.blindway.trip.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

public record TrackPointInput(
        @NotNull Instant recordedAt,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull @PositiveOrZero @DecimalMax("1000") Double accuracyMeters,
        @PositiveOrZero Double speedMetersPerSecond) {}

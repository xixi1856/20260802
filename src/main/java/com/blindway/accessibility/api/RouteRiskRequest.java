package com.blindway.accessibility.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record RouteRiskRequest(
        @NotEmpty @Size(min = 2, max = 500) List<@Valid Point> points, @Min(5) @Max(100) Integer corridorMeters) {

    public record Point(
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude) {}
}

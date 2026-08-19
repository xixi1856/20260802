package com.blindway.accessibility.api;

import com.blindway.accessibility.domain.IssueType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIssueRequest(
        @NotNull IssueType type,
        @NotBlank @Size(max = 1000) String description,
        @NotNull @Min(1) @Max(5) Integer severity,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude) {}

package com.blindway.accessibility.api;

import com.blindway.accessibility.domain.IssueStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TransitionIssueRequest(
        @NotNull IssueStatus targetStatus,
        @NotBlank @Size(max = 500) String reason,
        @NotNull @PositiveOrZero Integer version) {}

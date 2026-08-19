package com.blindway.accessibility.infrastructure;

import com.blindway.accessibility.domain.IssueType;
import java.util.UUID;

public record RouteRiskRow(
        UUID issueId,
        IssueType type,
        int severity,
        int confidenceScore,
        double distanceToRouteMeters,
        int contribution) {}

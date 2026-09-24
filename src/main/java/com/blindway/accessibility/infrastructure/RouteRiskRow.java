package com.blindway.accessibility.infrastructure;

import com.blindway.accessibility.domain.IssueType;
import java.util.UUID;

public record RouteRiskRow(
        UUID issueId,
        IssueType type,
        String riskLevel,
        double longitude,
        double latitude,
        double nearestLongitude,
        double nearestLatitude,
        double distanceToRouteMeters) {}

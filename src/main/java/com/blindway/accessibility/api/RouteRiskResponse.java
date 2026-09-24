package com.blindway.accessibility.api;

import java.util.List;

public record RouteRiskResponse(
        int highCount, int mediumCount, int lowCount, int corridorMeters, List<RiskIssue> issues) {

    public record RiskIssue(
            java.util.UUID issueId,
            String type,
            String riskLevel,
            double longitude,
            double latitude,
            double nearestLongitude,
            double nearestLatitude,
            double distanceToRouteMeters) {}
}

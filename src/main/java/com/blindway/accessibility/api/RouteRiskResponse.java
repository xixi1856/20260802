package com.blindway.accessibility.api;

import java.util.List;

public record RouteRiskResponse(
        int riskScore, String riskLevel, int issueCount, int corridorMeters, List<RiskIssue> issues) {

    public record RiskIssue(
            java.util.UUID issueId,
            String type,
            int severity,
            int confidenceScore,
            double distanceToRouteMeters,
            int contribution) {}
}

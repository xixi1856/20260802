package com.blindway.map.api;

import com.blindway.accessibility.RouteIssueAccess;
import java.util.List;

public record WalkingRoutesResponse(List<Route> routes) {

    public record Route(
            int providerIndex,
            int distanceMeters,
            int durationSeconds,
            String polylineGcj02,
            int highCount,
            int mediumCount,
            int lowCount,
            List<RouteIssueAccess.Issue> issues) {}
}

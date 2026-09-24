package com.blindway.accessibility;

import java.util.List;
import java.util.UUID;

public interface RouteIssueAccess {

    Inspection inspect(List<Point> points, int corridorMeters);

    record Point(double longitude, double latitude) {}

    record Issue(
            UUID issueId,
            String type,
            String riskLevel,
            double longitude,
            double latitude,
            double nearestLongitude,
            double nearestLatitude,
            double distanceToRouteMeters) {}

    record Inspection(int highCount, int mediumCount, int lowCount, int corridorMeters, List<Issue> issues) {}
}

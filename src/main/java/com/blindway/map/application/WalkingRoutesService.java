package com.blindway.map.application;

import com.blindway.accessibility.RouteIssueAccess;
import com.blindway.map.MapProvider;
import com.blindway.map.api.WalkingRoutesResponse;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.stereotype.Service;

@Service
public class WalkingRoutesService {

    private final MapProvider provider;
    private final RouteIssueAccess issues;

    public WalkingRoutesService(MapProvider provider, RouteIssueAccess issues) {
        this.provider = provider;
        this.issues = issues;
    }

    public WalkingRoutesResponse walkingRoutes(
            double originLongitude, double originLatitude, double destinationLongitude, double destinationLatitude) {
        var candidates =
                provider.walkingRoutes(originLongitude, originLatitude, destinationLongitude, destinationLatitude);
        var result = new ArrayList<WalkingRoutesResponse.Route>();
        for (int index = 0; index < candidates.size(); index++) {
            var route = candidates.get(index);
            var points = route.pointsWgs84().stream()
                    .map(point -> new RouteIssueAccess.Point(point.longitude(), point.latitude()))
                    .toList();
            var inspection = issues.inspect(points, 20);
            result.add(new WalkingRoutesResponse.Route(
                    index,
                    route.distanceMeters(),
                    route.durationSeconds(),
                    route.polylineGcj02(),
                    inspection.highCount(),
                    inspection.mediumCount(),
                    inspection.lowCount(),
                    inspection.issues()));
        }
        result.sort(Comparator.comparingInt(WalkingRoutesResponse.Route::highCount)
                .thenComparingInt(WalkingRoutesResponse.Route::mediumCount)
                .thenComparingInt(WalkingRoutesResponse.Route::lowCount)
                .thenComparingInt(WalkingRoutesResponse.Route::distanceMeters)
                .thenComparingInt(WalkingRoutesResponse.Route::providerIndex));
        return new WalkingRoutesResponse(result);
    }
}

package com.blindway.map.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.blindway.accessibility.RouteIssueAccess;
import com.blindway.map.MapProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalkingRoutesServiceTest {

    @Mock
    private MapProvider provider;

    @Mock
    private RouteIssueAccess issues;

    @Test
    void sortsByRiskCountsThenDistance() {
        var points = List.of(new MapProvider.Point(116.3, 39.9), new MapProvider.Point(116.4, 39.91));
        when(provider.walkingRoutes(116.3, 39.9, 116.4, 39.91))
                .thenReturn(List.of(route(900, points), route(800, points), route(700, points)));
        var inspected = points.stream()
                .map(point -> new RouteIssueAccess.Point(point.longitude(), point.latitude()))
                .toList();
        when(issues.inspect(inspected, 20))
                .thenReturn(
                        new RouteIssueAccess.Inspection(1, 0, 0, 20, List.of()),
                        new RouteIssueAccess.Inspection(0, 2, 0, 20, List.of()),
                        new RouteIssueAccess.Inspection(0, 1, 3, 20, List.of()));

        var result = new WalkingRoutesService(provider, issues).walkingRoutes(116.3, 39.9, 116.4, 39.91);

        assertThat(result.routes()).extracting(route -> route.providerIndex()).containsExactly(2, 1, 0);
    }

    private MapProvider.WalkingRoute route(int distance, List<MapProvider.Point> points) {
        return new MapProvider.WalkingRoute(distance, 600, "116.3,39.9;116.4,39.91", points);
    }
}

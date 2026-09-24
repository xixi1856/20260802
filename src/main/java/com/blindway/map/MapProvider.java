package com.blindway.map;

import java.util.List;

public interface MapProvider {

    ReverseGeocode reverseGeocode(double wgs84Longitude, double wgs84Latitude);

    List<WalkingRoute> walkingRoutes(
            double originWgs84Longitude,
            double originWgs84Latitude,
            double destinationWgs84Longitude,
            double destinationWgs84Latitude);

    record ReverseGeocode(String formattedAddress) {}

    record WalkingRoute(int distanceMeters, int durationSeconds, String polylineGcj02, List<Point> pointsWgs84) {}

    record Point(double longitude, double latitude) {}
}

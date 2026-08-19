package com.blindway.map;

public interface MapProvider {

    ReverseGeocode reverseGeocode(double wgs84Longitude, double wgs84Latitude);

    WalkingRoute walkingRoute(
            double originWgs84Longitude,
            double originWgs84Latitude,
            double destinationWgs84Longitude,
            double destinationWgs84Latitude);

    record ReverseGeocode(String formattedAddress) {}

    record WalkingRoute(int distanceMeters, int durationSeconds, String polylineGcj02) {}
}

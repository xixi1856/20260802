package com.blindway.map.infrastructure;

final class Gcj02Coordinates {

    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private Gcj02Coordinates() {}

    static Point toWgs84(double longitude, double latitude) {
        if (longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271) {
            return new Point(longitude, latitude);
        }
        double wgsLongitude = longitude;
        double wgsLatitude = latitude;
        for (int attempt = 0; attempt < 6; attempt++) {
            Point gcj = toGcj02(wgsLongitude, wgsLatitude);
            wgsLongitude += longitude - gcj.longitude();
            wgsLatitude += latitude - gcj.latitude();
        }
        return new Point(wgsLongitude, wgsLatitude);
    }

    private static Point toGcj02(double longitude, double latitude) {
        double deltaLongitude = transformLongitude(longitude - 105, latitude - 35);
        double deltaLatitude = transformLatitude(longitude - 105, latitude - 35);
        double radian = latitude / 180 * Math.PI;
        double sin = Math.sin(radian);
        double magic = 1 - EE * sin * sin;
        double sqrt = Math.sqrt(magic);
        deltaLatitude = deltaLatitude * 180 / ((A * (1 - EE)) / (magic * sqrt) * Math.PI);
        deltaLongitude = deltaLongitude * 180 / (A / sqrt * Math.cos(radian) * Math.PI);
        return new Point(longitude + deltaLongitude, latitude + deltaLatitude);
    }

    private static double transformLatitude(double x, double y) {
        double result = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x));
        result += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        result += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
        return result + (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y / 30 * Math.PI)) * 2 / 3;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x));
        result += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        result += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
        return result + (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
    }

    record Point(double longitude, double latitude) {}
}

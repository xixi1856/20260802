package com.blindway.trip.api;

import java.util.List;

public record TripPageResponse(List<TripResponse> items, int page, int size, long total) {}

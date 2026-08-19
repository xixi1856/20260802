package com.blindway.trip.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StartTripRequest(@NotNull UUID deviceId) {}

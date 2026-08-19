package com.blindway.trip.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TrackPointBatchRequest(@NotEmpty @Size(max = 100) List<@Valid TrackPointInput> points) {}

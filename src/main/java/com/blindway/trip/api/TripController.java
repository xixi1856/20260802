package com.blindway.trip.api;

import com.blindway.common.security.CurrentUser;
import com.blindway.trip.application.TripService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService service;

    public TripController(TripService service) {
        this.service = service;
    }

    @GetMapping
    TripPageResponse history(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return service.history(CurrentUser.id(jwt), page, size);
    }

    @GetMapping("/{tripId}")
    TripResponse get(@PathVariable UUID tripId, @AuthenticationPrincipal Jwt jwt) {
        return service.get(CurrentUser.id(jwt), tripId);
    }

    @GetMapping("/{tripId}/track-points")
    TrackPointPageResponse trackPoints(
            @PathVariable UUID tripId,
            @RequestParam(required = false) Instant after,
            @RequestParam(defaultValue = "500") @Min(1) @Max(1000) int size,
            @AuthenticationPrincipal Jwt jwt) {
        return service.trackPoints(CurrentUser.id(jwt), tripId, after, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TripResponse start(@Valid @RequestBody StartTripRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.start(CurrentUser.id(jwt), request.deviceId());
    }

    @PostMapping("/{tripId}/track-points")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Integer> appendTrackPoints(
            @PathVariable UUID tripId,
            @Valid @RequestBody TrackPointBatchRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return Map.of("inserted", service.appendTrackPoints(CurrentUser.id(jwt), tripId, request.points()));
    }

    @PostMapping("/{tripId}/completion")
    TripResponse complete(@PathVariable UUID tripId, @AuthenticationPrincipal Jwt jwt) {
        return service.complete(CurrentUser.id(jwt), tripId);
    }
}

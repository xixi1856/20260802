package com.blindway.map.api;

import com.blindway.map.MapProvider;
import com.blindway.map.application.WalkingRoutesService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/map")
public class MapController {

    private final MapProvider provider;
    private final WalkingRoutesService walkingRoutesService;

    public MapController(MapProvider provider, WalkingRoutesService walkingRoutesService) {
        this.provider = provider;
        this.walkingRoutesService = walkingRoutesService;
    }

    @GetMapping("/reverse-geocode")
    MapProvider.ReverseGeocode reverseGeocode(
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude) {
        return provider.reverseGeocode(longitude, latitude);
    }

    @GetMapping("/walking-routes")
    WalkingRoutesResponse walkingRoute(
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double originLongitude,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double originLatitude,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double destinationLongitude,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double destinationLatitude) {
        return walkingRoutesService.walkingRoutes(
                originLongitude, originLatitude, destinationLongitude, destinationLatitude);
    }
}

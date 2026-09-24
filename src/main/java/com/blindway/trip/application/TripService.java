package com.blindway.trip.application;

import com.blindway.common.api.ApiException;
import com.blindway.trip.TripLocationAccess;
import com.blindway.trip.api.TrackPointInput;
import com.blindway.trip.api.TrackPointPageResponse;
import com.blindway.trip.api.TripPageResponse;
import com.blindway.trip.api.TripResponse;
import com.blindway.trip.infrastructure.TrackPointMatchRow;
import com.blindway.trip.infrastructure.TripMapper;
import com.blindway.trip.infrastructure.TripRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripService implements TripLocationAccess {

    private final TripMapper mapper;

    public TripService(TripMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public TripResponse start(UUID userId, UUID deviceId) {
        if (mapper.countOwnedActiveDevice(deviceId, userId) != 1) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_NOT_OWNED", "设备未绑定到当前用户或不可用");
        }
        Instant now = Instant.now();
        TripRow trip = new TripRow(UUID.randomUUID(), userId, deviceId, "ACTIVE", now, null, now, now);
        try {
            mapper.insertTrip(trip);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_HAS_ACTIVE_TRIP", "设备已有进行中的行程");
        }
        return response(trip);
    }

    @Transactional(readOnly = true)
    public TripPageResponse history(UUID userId, int page, int size) {
        long offset = (long) page * size;
        return new TripPageResponse(
                mapper.findByUser(userId, size, offset).stream()
                        .map(this::response)
                        .toList(),
                page,
                size,
                mapper.countByUser(userId));
    }

    @Transactional(readOnly = true)
    public TripResponse get(UUID userId, UUID tripId) {
        return response(ownedTrip(userId, tripId));
    }

    @Transactional(readOnly = true)
    public TrackPointPageResponse trackPoints(UUID userId, UUID tripId, Instant after, int size) {
        ownedTrip(userId, tripId);
        var rows = mapper.findTrackPoints(tripId, after, size + 1);
        boolean more = rows.size() > size;
        var page = more ? rows.subList(0, size) : rows;
        return new TrackPointPageResponse(
                page.stream()
                        .map(row -> new TrackPointPageResponse.TrackPoint(
                                row.id(),
                                row.recordedAt(),
                                row.longitude(),
                                row.latitude(),
                                row.accuracyMeters(),
                                row.speedMetersPerSecond()))
                        .toList(),
                more ? page.getLast().recordedAt() : null);
    }

    @Transactional
    public int appendTrackPoints(UUID userId, UUID tripId, List<TrackPointInput> points) {
        TripRow trip = ownedActiveTrip(userId, tripId);
        Instant now = Instant.now();
        for (TrackPointInput point : points) {
            if (point.recordedAt().isBefore(trip.startedAt().minusSeconds(5))
                    || point.recordedAt().isAfter(now.plusSeconds(30))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TRACK_TIME", "轨迹时间超出允许范围");
            }
        }
        return mapper.insertTrackPoints(tripId, points, now);
    }

    @Transactional
    public TripResponse complete(UUID userId, UUID tripId) {
        Instant now = Instant.now();
        if (mapper.complete(tripId, userId, now) != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACTIVE_TRIP_NOT_FOUND", "进行中的行程不存在");
        }
        return mapper.findById(tripId).map(this::response).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public LocationMatch resolve(UUID deviceId, UUID requestedTripId, Instant occurredAt) {
        TripRow trip;
        if (requestedTripId != null) {
            trip = mapper.findById(requestedTripId)
                    .filter(value -> value.deviceId().equals(deviceId))
                    .filter(value -> !occurredAt.isBefore(value.startedAt()))
                    .filter(value -> value.endedAt() == null || !occurredAt.isAfter(value.endedAt()))
                    .orElse(null);
        } else {
            trip = mapper.findActiveForDeviceAt(deviceId, occurredAt).orElse(null);
        }
        if (trip == null) {
            return LocationMatch.unmatched(null, "NO_TRIP");
        }
        TrackPointMatchRow before =
                mapper.findTrackPointBefore(trip.id(), occurredAt).orElse(null);
        TrackPointMatchRow after =
                mapper.findTrackPointAfter(trip.id(), occurredAt).orElse(null);
        if (before == null && after == null) {
            return LocationMatch.unmatched(trip.id(), "NO_TRACK_WITHIN_WINDOW");
        }
        if (before != null && after != null && !before.recordedAt().equals(after.recordedAt())) {
            return interpolate(trip.id(), before, after, occurredAt);
        }
        TrackPointMatchRow point = before != null ? before : after;
        long offsetMs =
                Math.abs(Duration.between(point.recordedAt(), occurredAt).toMillis());
        String quality = point.accuracyMeters() <= 15 && offsetMs <= 3000 ? "MEDIUM" : "LOW";
        return new LocationMatch(
                trip.id(), point.id(), point.longitude(), point.latitude(), "NEAREST", quality, offsetMs);
    }

    private LocationMatch interpolate(
            UUID tripId, TrackPointMatchRow before, TrackPointMatchRow after, Instant occurredAt) {
        long spanMs = Duration.between(before.recordedAt(), after.recordedAt()).toMillis();
        long elapsedMs = Duration.between(before.recordedAt(), occurredAt).toMillis();
        double ratio = Math.max(0, Math.min(1, (double) elapsedMs / spanMs));
        double longitude = before.longitude() + (after.longitude() - before.longitude()) * ratio;
        double latitude = before.latitude() + (after.latitude() - before.latitude()) * ratio;
        long offsetMs = Math.min(elapsedMs, spanMs - elapsedMs);
        double worstAccuracy = Math.max(before.accuracyMeters(), after.accuracyMeters());
        String quality = spanMs <= 5000 && worstAccuracy <= 15 ? "HIGH" : spanMs <= 10000 ? "MEDIUM" : "LOW";
        Long nearestId = elapsedMs <= spanMs - elapsedMs ? before.id() : after.id();
        return new LocationMatch(tripId, nearestId, longitude, latitude, "INTERPOLATED", quality, offsetMs);
    }

    private TripRow ownedActiveTrip(UUID userId, UUID tripId) {
        return java.util.Optional.of(ownedTrip(userId, tripId))
                .filter(trip -> "ACTIVE".equals(trip.status()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACTIVE_TRIP_NOT_FOUND", "进行中的行程不存在"));
    }

    private TripRow ownedTrip(UUID userId, UUID tripId) {
        return mapper.findById(tripId)
                .filter(trip -> trip.userId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "行程不存在"));
    }

    private TripResponse response(TripRow row) {
        return new TripResponse(row.id(), row.deviceId(), row.status(), row.startedAt(), row.endedAt());
    }
}

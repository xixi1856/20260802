package com.blindway.trip.infrastructure;

import com.blindway.trip.api.TrackPointInput;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TripMapper {

    @Select(
            """
            SELECT id, user_id, device_id, status, started_at, ended_at, created_at, updated_at
            FROM trip
            WHERE id = #{id}
            """)
    Optional<TripRow> findById(UUID id);

    @Select(
            """
            SELECT id, user_id, device_id, status, started_at, ended_at, created_at, updated_at
            FROM trip WHERE user_id = #{userId}
            ORDER BY started_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<TripRow> findByUser(@Param("userId") UUID userId, @Param("size") int size, @Param("offset") long offset);

    @Select("SELECT count(*) FROM trip WHERE user_id = #{userId}")
    long countByUser(UUID userId);

    @Select(
            """
            SELECT id, recorded_at, ST_X(location::geometry) AS longitude,
                   ST_Y(location::geometry) AS latitude, accuracy_meters, speed_meters_per_second
            FROM trip_track_point
            WHERE trip_id = #{tripId}
              AND (#{after}::timestamptz IS NULL OR recorded_at > #{after}::timestamptz)
            ORDER BY recorded_at, id
            LIMIT #{limit}
            """)
    List<TrackPointRow> findTrackPoints(
            @Param("tripId") UUID tripId, @Param("after") Instant after, @Param("limit") int limit);

    @Select(
            """
            SELECT t.id, t.user_id, t.device_id, t.status, t.started_at, t.ended_at, t.created_at, t.updated_at
            FROM trip t
            JOIN device d ON d.id = t.device_id
            WHERE t.device_id = #{deviceId} AND t.status = 'ACTIVE'
              AND t.started_at <= #{occurredAt}
              AND (t.ended_at IS NULL OR t.ended_at >= #{occurredAt})
            """)
    Optional<TripRow> findActiveForDeviceAt(@Param("deviceId") UUID deviceId, @Param("occurredAt") Instant occurredAt);

    @Select(
            """
            SELECT count(*)
            FROM device
            WHERE id = #{deviceId} AND owner_user_id = #{userId} AND status = 'ACTIVE'
            """)
    int countOwnedActiveDevice(@Param("deviceId") UUID deviceId, @Param("userId") UUID userId);

    @Insert(
            """
            INSERT INTO trip
                (id, user_id, device_id, status, started_at, ended_at, created_at, updated_at)
            VALUES
                (#{id}, #{userId}, #{deviceId}, #{status}, #{startedAt}, #{endedAt}, #{createdAt}, #{updatedAt})
            """)
    void insertTrip(TripRow trip);

    @Insert(
            """
            <script>
            INSERT INTO trip_track_point
                (trip_id, recorded_at, location, accuracy_meters, speed_meters_per_second, created_at)
            VALUES
            <foreach collection="points" item="point" separator=",">
                (#{tripId}, #{point.recordedAt},
                 ST_SetSRID(ST_MakePoint(#{point.longitude}, #{point.latitude}), 4326)::geography,
                 #{point.accuracyMeters}, #{point.speedMetersPerSecond}, #{createdAt})
            </foreach>
            ON CONFLICT (trip_id, recorded_at) DO NOTHING
            </script>
            """)
    int insertTrackPoints(
            @Param("tripId") UUID tripId,
            @Param("points") List<TrackPointInput> points,
            @Param("createdAt") Instant createdAt);

    @Update(
            """
            UPDATE trip
            SET status = 'COMPLETED', ended_at = #{endedAt}, updated_at = #{endedAt}
            WHERE id = #{tripId} AND user_id = #{userId} AND status = 'ACTIVE'
            """)
    int complete(@Param("tripId") UUID tripId, @Param("userId") UUID userId, @Param("endedAt") Instant endedAt);

    @Select(
            """
            SELECT id, ST_X(location::geometry) AS longitude, ST_Y(location::geometry) AS latitude,
                   recorded_at, accuracy_meters
            FROM trip_track_point
            WHERE trip_id = #{tripId} AND accuracy_meters <= 50
              AND recorded_at BETWEEN CAST(#{occurredAt} AS timestamp with time zone) - INTERVAL '10 seconds'
                                  AND CAST(#{occurredAt} AS timestamp with time zone)
            ORDER BY recorded_at DESC
            LIMIT 1
            """)
    Optional<TrackPointMatchRow> findTrackPointBefore(
            @Param("tripId") UUID tripId, @Param("occurredAt") Instant occurredAt);

    @Select(
            """
            SELECT id, ST_X(location::geometry) AS longitude, ST_Y(location::geometry) AS latitude,
                   recorded_at, accuracy_meters
            FROM trip_track_point
            WHERE trip_id = #{tripId} AND accuracy_meters <= 50
              AND recorded_at BETWEEN CAST(#{occurredAt} AS timestamp with time zone)
                                  AND CAST(#{occurredAt} AS timestamp with time zone) + INTERVAL '10 seconds'
            ORDER BY recorded_at
            LIMIT 1
            """)
    Optional<TrackPointMatchRow> findTrackPointAfter(
            @Param("tripId") UUID tripId, @Param("occurredAt") Instant occurredAt);
}

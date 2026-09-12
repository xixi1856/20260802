package com.blindway.insight.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InsightMapper {

    @Insert(
            """
            INSERT INTO kafka_consumed_event (consumer_name, event_id, consumed_at)
            VALUES (#{consumerName}, #{eventId}, #{consumedAt})
            ON CONFLICT (consumer_name, event_id) DO NOTHING
            """)
    int recordConsumption(
            @Param("consumerName") String consumerName,
            @Param("eventId") UUID eventId,
            @Param("consumedAt") Instant consumedAt);

    @Insert(
            """
            INSERT INTO community_candidate_projection
                (event_id, device_id, trip_id, category, location, location_quality, occurred_at)
            VALUES
                (#{eventId}, #{deviceId}, #{tripId}, #{category},
                 ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography,
                 #{locationQuality}, #{occurredAt})
            ON CONFLICT (event_id) DO NOTHING
            """)
    int insertCommunityCandidate(
            @Param("eventId") UUID eventId,
            @Param("deviceId") UUID deviceId,
            @Param("tripId") UUID tripId,
            @Param("category") String category,
            @Param("longitude") double longitude,
            @Param("latitude") double latitude,
            @Param("locationQuality") String locationQuality,
            @Param("occurredAt") Instant occurredAt);

    @Insert(
            """
            INSERT INTO trip_risk_projection
                (trip_id, obstacle_event_count, path_lost_event_count, latest_event_at, updated_at)
            VALUES
                (#{tripId}, #{obstacleDelta}, #{pathLostDelta}, #{occurredAt}, now())
            ON CONFLICT (trip_id) DO UPDATE
            SET obstacle_event_count = trip_risk_projection.obstacle_event_count + EXCLUDED.obstacle_event_count,
                path_lost_event_count = trip_risk_projection.path_lost_event_count + EXCLUDED.path_lost_event_count,
                latest_event_at = GREATEST(trip_risk_projection.latest_event_at, EXCLUDED.latest_event_at),
                updated_at = now()
            """)
    int incrementTripRisk(
            @Param("tripId") UUID tripId,
            @Param("obstacleDelta") int obstacleDelta,
            @Param("pathLostDelta") int pathLostDelta,
            @Param("occurredAt") Instant occurredAt);

    @Insert(
            """
            INSERT INTO device_event_daily_projection
                (device_id, event_day, heartbeat_count, path_event_count, obstacle_event_count,
                 latest_event_at, updated_at)
            VALUES
                (#{deviceId}, #{eventDay}, #{heartbeatDelta}, #{pathDelta}, #{obstacleDelta}, #{occurredAt}, now())
            ON CONFLICT (device_id, event_day) DO UPDATE
            SET heartbeat_count = device_event_daily_projection.heartbeat_count + EXCLUDED.heartbeat_count,
                path_event_count = device_event_daily_projection.path_event_count + EXCLUDED.path_event_count,
                obstacle_event_count = device_event_daily_projection.obstacle_event_count + EXCLUDED.obstacle_event_count,
                latest_event_at = GREATEST(device_event_daily_projection.latest_event_at, EXCLUDED.latest_event_at),
                updated_at = now()
            """)
    int incrementDeviceDaily(
            @Param("deviceId") UUID deviceId,
            @Param("eventDay") LocalDate eventDay,
            @Param("heartbeatDelta") int heartbeatDelta,
            @Param("pathDelta") int pathDelta,
            @Param("obstacleDelta") int obstacleDelta,
            @Param("occurredAt") Instant occurredAt);
}

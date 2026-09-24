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
            INSERT INTO application_log_archive
                (log_id, occurred_at, level, logger, message, service, instance, trace_id)
            VALUES (#{logId}, #{occurredAt}, #{level}, #{logger}, #{message}, #{service}, #{instance}, #{traceId})
            ON CONFLICT (log_id) DO NOTHING
            """)
    int archiveApplicationLog(
            @Param("logId") UUID logId,
            @Param("occurredAt") Instant occurredAt,
            @Param("level") String level,
            @Param("logger") String logger,
            @Param("message") String message,
            @Param("service") String service,
            @Param("instance") String instance,
            @Param("traceId") String traceId);

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

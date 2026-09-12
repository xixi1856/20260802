package com.blindway.perception.infrastructure;

import com.blindway.perception.domain.HeartbeatPayload;
import com.blindway.perception.domain.MqttEnvelope;
import com.blindway.perception.domain.ObstacleEventPayload;
import com.blindway.perception.domain.PathEventPayload;
import com.blindway.trip.TripLocationAccess.LocationMatch;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PerceptionMapper {

    @Insert(
            """
            INSERT INTO mqtt_inbox
                (event_id, device_id, trip_id, topic, schema_version, boot_id, sequence_no,
                 occurred_at, sent_at, received_at, raw_payload, process_status)
            VALUES
                (#{envelope.eventId}, #{envelope.deviceId}, #{envelope.tripId}, #{topic},
                 #{envelope.schemaVersion}, #{envelope.bootId}, #{envelope.sequenceNo},
                 #{envelope.occurredAt}, #{envelope.sentAt}, #{receivedAt},
                 CAST(#{rawPayload} AS jsonb), 'PENDING')
            ON CONFLICT (event_id) DO NOTHING
            """)
    int insertInbox(
            @Param("envelope") MqttEnvelope envelope,
            @Param("topic") String topic,
            @Param("receivedAt") Instant receivedAt,
            @Param("rawPayload") String rawPayload);

    @Select(
            """
            WITH candidates AS (
                SELECT event_id
                FROM mqtt_inbox
                WHERE (process_status = 'PENDING' AND next_attempt_at <= #{now})
                   OR (process_status = 'PROCESSING' AND lease_until < #{now})
                ORDER BY next_attempt_at, received_at
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE mqtt_inbox inbox
            SET process_status = 'PROCESSING', lease_owner = #{owner}, lease_until = #{leaseUntil},
                attempt_count = inbox.attempt_count + 1
            FROM candidates
            WHERE inbox.event_id = candidates.event_id
            RETURNING inbox.event_id, inbox.topic, inbox.raw_payload::text AS raw_payload,
                      inbox.received_at, inbox.attempt_count
            """)
    List<MqttInboxRow> claimInboxBatch(
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("limit") int limit);

    @Update(
            """
            UPDATE mqtt_inbox
            SET process_status = 'PROCESSED', error_code = NULL, last_error = NULL,
                processed_at = #{processedAt}, trip_id = COALESCE(#{tripId}, trip_id),
                lease_owner = NULL, lease_until = NULL
            WHERE event_id = #{eventId} AND process_status = 'PROCESSING' AND lease_owner = #{owner}
            """)
    int markProcessed(
            @Param("eventId") UUID eventId,
            @Param("owner") String owner,
            @Param("processedAt") Instant processedAt,
            @Param("tripId") UUID tripId);

    @Update(
            """
            UPDATE mqtt_inbox
            SET process_status = 'REJECTED', error_code = #{errorCode}, last_error = NULL,
                processed_at = #{processedAt}, lease_owner = NULL, lease_until = NULL
            WHERE event_id = #{eventId} AND process_status = 'PROCESSING' AND lease_owner = #{owner}
            """)
    int markRejected(
            @Param("eventId") UUID eventId,
            @Param("owner") String owner,
            @Param("errorCode") String errorCode,
            @Param("processedAt") Instant processedAt);

    @Update(
            """
            UPDATE mqtt_inbox
            SET process_status = #{status}, last_error = left(#{error}, 500),
                next_attempt_at = #{nextAttemptAt}, processed_at = CASE WHEN #{status} = 'DEAD' THEN now() ELSE NULL END,
                lease_owner = NULL, lease_until = NULL
            WHERE event_id = #{eventId} AND process_status = 'PROCESSING' AND lease_owner = #{owner}
            """)
    int markInboxFailed(
            @Param("eventId") UUID eventId,
            @Param("owner") String owner,
            @Param("status") String status,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt);

    @Update(
            """
            UPDATE mqtt_inbox
            SET process_status = 'PENDING', attempt_count = 0, next_attempt_at = #{replayedAt},
                lease_owner = NULL, lease_until = NULL, error_code = NULL, last_error = NULL,
                processed_at = NULL
            WHERE event_id = #{eventId} AND process_status IN ('DEAD', 'REJECTED')
            """)
    int replayInbox(@Param("eventId") UUID eventId, @Param("replayedAt") Instant replayedAt);

    @Select("SELECT count(*) FROM mqtt_inbox WHERE process_status = #{status}")
    long countInboxByStatus(String status);

    @Select(
            """
            SELECT COALESCE(EXTRACT(EPOCH FROM (#{now} - min(received_at))), 0)::bigint
            FROM mqtt_inbox
            WHERE process_status IN ('PENDING', 'PROCESSING')
            """)
    long oldestPendingAgeSeconds(Instant now);

    @Insert(
            """
            INSERT INTO device_heartbeat
                (event_id, device_id, boot_id, sequence_no, occurred_at, received_at,
                 software_version, model_version, config_version, uptime_seconds,
                 cpu_temperature_c, cpu_usage_percent, memory_usage_percent, disk_usage_percent,
                 inference_fps, average_inference_latency_ms, network_type, signal_dbm,
                 camera_status, stereo_status, inference_status, clock_synchronized,
                 pending_event_count, dropped_event_count)
            VALUES
                (#{envelope.eventId}, #{envelope.deviceId}, #{envelope.bootId}, #{envelope.sequenceNo},
                 #{envelope.occurredAt}, #{receivedAt}, #{payload.softwareVersion},
                 #{payload.modelVersion}, #{payload.configVersion}, #{payload.uptimeSeconds},
                 #{payload.cpuTemperatureC}, #{payload.cpuUsagePercent}, #{payload.memoryUsagePercent},
                 #{payload.diskUsagePercent}, #{payload.inferenceFps},
                 #{payload.averageInferenceLatencyMs}, #{payload.networkType}, #{payload.signalDbm},
                 #{payload.cameraStatus}, #{payload.stereoStatus}, #{payload.inferenceStatus},
                 #{payload.clockSynchronized}, COALESCE(#{payload.pendingEventCount}, 0),
                 COALESCE(#{payload.droppedEventCount}, 0))
            """)
    void insertHeartbeat(
            @Param("envelope") MqttEnvelope envelope,
            @Param("payload") HeartbeatPayload payload,
            @Param("receivedAt") Instant receivedAt);

    @Insert(
            """
            INSERT INTO path_observation
                (event_id, device_id, trip_id, matched_track_point_id, location,
                 location_match_status, location_quality, location_time_offset_ms,
                 state, confidence, nearest_distance_meters,
                 lateral_offset_meters, measurement_quality, processing_latency_ms,
                 occurred_at, received_at)
            VALUES
                (#{envelope.eventId}, #{envelope.deviceId}, #{match.tripId}, #{match.trackPointId},
                 CASE WHEN CAST(#{match.longitude} AS double precision) IS NULL THEN NULL
                      ELSE ST_SetSRID(ST_MakePoint(#{match.longitude}, #{match.latitude}), 4326)::geography END,
                 #{match.status}, #{match.quality}, #{match.timeOffsetMs}, #{payload.state}, #{payload.confidence},
                 #{payload.nearestDistanceMeters}, #{payload.lateralOffsetMeters},
                 #{payload.measurementQuality}, #{payload.processingLatencyMs},
                 #{envelope.occurredAt}, #{receivedAt})
            """)
    void insertPathObservation(
            @Param("envelope") MqttEnvelope envelope,
            @Param("payload") PathEventPayload payload,
            @Param("match") LocationMatch match,
            @Param("receivedAt") Instant receivedAt);

    @Insert(
            """
            INSERT INTO obstacle_event
                (event_id, device_id, trip_id, matched_track_point_id, location,
                 location_match_status, location_quality, location_time_offset_ms,
                 tactile_corridor_confidence, warning_threshold_meters,
                 minimum_distance_meters, processing_latency_ms, obstacles, occurred_at, received_at)
            VALUES
                (#{envelope.eventId}, #{envelope.deviceId}, #{match.tripId}, #{match.trackPointId},
                 CASE WHEN CAST(#{match.longitude} AS double precision) IS NULL THEN NULL
                      ELSE ST_SetSRID(ST_MakePoint(#{match.longitude}, #{match.latitude}), 4326)::geography END,
                 #{match.status}, #{match.quality}, #{match.timeOffsetMs},
                 #{payload.tactileCorridorConfidence}, #{payload.warningThresholdMeters},
                 #{payload.minimumDistanceMeters}, #{payload.processingLatencyMs},
                 CAST(#{obstaclesJson} AS jsonb), #{envelope.occurredAt}, #{receivedAt})
            """)
    void insertObstacleEvent(
            @Param("envelope") MqttEnvelope envelope,
            @Param("payload") ObstacleEventPayload payload,
            @Param("obstaclesJson") String obstaclesJson,
            @Param("match") LocationMatch match,
            @Param("receivedAt") Instant receivedAt);

    @Delete("DELETE FROM device_heartbeat WHERE occurred_at < #{cutoff}")
    int deleteOldHeartbeats(Instant cutoff);

    @Delete("DELETE FROM path_observation WHERE occurred_at < #{cutoff}")
    int deleteOldPathObservations(Instant cutoff);

    @Delete("DELETE FROM obstacle_event WHERE occurred_at < #{cutoff}")
    int deleteOldObstacleEvents(Instant cutoff);

    @Delete(
            """
            DELETE FROM mqtt_inbox i
            WHERE i.received_at < #{cutoff}
              AND NOT EXISTS (SELECT 1 FROM path_observation p WHERE p.event_id = i.event_id)
              AND NOT EXISTS (SELECT 1 FROM obstacle_event o WHERE o.event_id = i.event_id)
            """)
    int deleteOldInbox(Instant cutoff);
}

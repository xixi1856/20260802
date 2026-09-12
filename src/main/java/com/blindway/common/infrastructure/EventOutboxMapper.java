package com.blindway.common.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EventOutboxMapper {

    @Insert(
            """
            INSERT INTO integration_event_outbox
                (id, aggregate_type, aggregate_id, event_type, topic, partition_key, payload,
                 status, attempt_count, next_attempt_at, created_at)
            VALUES
                (#{id}, #{aggregateType}, #{aggregateId}, #{eventType}, #{topic}, #{partitionKey},
                 CAST(#{payload} AS jsonb), 'PENDING', 0, #{createdAt}, #{createdAt})
            ON CONFLICT (id) DO NOTHING
            """)
    int insert(
            @Param("id") UUID id,
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("eventType") String eventType,
            @Param("topic") String topic,
            @Param("partitionKey") String partitionKey,
            @Param("payload") String payload,
            @Param("createdAt") Instant createdAt);

    @Select(
            """
            WITH candidates AS (
                SELECT id
                FROM integration_event_outbox
                WHERE (status = 'PENDING' AND next_attempt_at <= #{now})
                   OR (status = 'PUBLISHING' AND lease_until < #{now})
                ORDER BY next_attempt_at, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE integration_event_outbox event
            SET status = 'PUBLISHING', lease_owner = #{owner}, lease_until = #{leaseUntil}
            FROM candidates
            WHERE event.id = candidates.id
            RETURNING event.id, event.topic, event.partition_key, event.event_type,
                      event.payload::text AS payload, event.attempt_count
            """)
    List<OutboxRow> claimBatch(
            @Param("owner") String owner,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("limit") int limit);

    @Update(
            """
            UPDATE integration_event_outbox
            SET status = 'PUBLISHED', published_at = #{publishedAt}, lease_owner = NULL,
                lease_until = NULL, last_error = NULL
            WHERE id = #{id} AND status = 'PUBLISHING' AND lease_owner = #{owner}
            """)
    int markPublished(@Param("id") UUID id, @Param("owner") String owner, @Param("publishedAt") Instant publishedAt);

    @Update(
            """
            UPDATE integration_event_outbox
            SET status = #{status}, attempt_count = #{attemptCount}, next_attempt_at = #{nextAttemptAt},
                lease_owner = NULL, lease_until = NULL, last_error = left(#{error}, 500)
            WHERE id = #{id} AND status = 'PUBLISHING' AND lease_owner = #{owner}
            """)
    int markFailed(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("attemptCount") int attemptCount,
            @Param("status") String status,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt);

    @Select("SELECT count(*) FROM integration_event_outbox WHERE status = #{status}")
    long countByStatus(String status);
}

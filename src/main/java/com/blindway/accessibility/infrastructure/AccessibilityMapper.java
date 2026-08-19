package com.blindway.accessibility.infrastructure;

import com.blindway.accessibility.domain.IssueType;
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
public interface AccessibilityMapper {

    @Select("SELECT pg_advisory_xact_lock(71001, hashtext(#{lockKey})) IS NULL")
    boolean lockAggregationBucket(@Param("lockKey") String lockKey);

    @Select(
            """
            SELECT id, reporter_user_id, type, description, status, severity, report_count,
                   confirmation_count, rejection_count, confidence_score,
                   (severity * confidence_score / 10)::integer AS risk_score, version,
                   ST_X(location::geometry) AS longitude, ST_Y(location::geometry) AS latitude,
                   ST_Distance(location, ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography)
                       AS distance_meters,
                   last_reported_at, created_at, updated_at
            FROM accessibility_issue
            WHERE type = #{type} AND status IN ('PENDING', 'VERIFIED', 'PROCESSING')
              AND last_reported_at >= #{since}
              AND ST_DWithin(location,
                  ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography, #{radiusMeters})
            ORDER BY distance_meters, last_reported_at DESC
            LIMIT 1
            FOR UPDATE
            """)
    Optional<IssueRow> findDuplicateForUpdate(
            @Param("type") IssueType type,
            @Param("longitude") double longitude,
            @Param("latitude") double latitude,
            @Param("radiusMeters") int radiusMeters,
            @Param("since") Instant since);

    @Insert(
            """
            INSERT INTO accessibility_issue
                (id, reporter_user_id, tactile_path_segment_id, type, description, status,
                 severity, report_count, confirmation_count, rejection_count, confidence_score,
                 location, last_reported_at, created_at, updated_at, resolved_at, resolved_by)
            VALUES
                (#{id}, #{reporterUserId}, NULL, #{type}, #{description}, 'PENDING',
                 #{severity}, 1, 0, 0, 20,
                 ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography,
                 #{createdAt}, #{createdAt}, #{createdAt}, NULL, NULL)
            """)
    void insertIssue(
            @Param("id") UUID id,
            @Param("reporterUserId") UUID reporterUserId,
            @Param("type") IssueType type,
            @Param("description") String description,
            @Param("severity") int severity,
            @Param("longitude") double longitude,
            @Param("latitude") double latitude,
            @Param("createdAt") Instant createdAt);

    @Update(
            """
            UPDATE accessibility_issue
            SET report_count = report_count + 1,
                severity = GREATEST(severity, #{severity}),
                confidence_score = LEAST(100, confidence_score + 15),
                last_reported_at = #{now}, updated_at = #{now}
            WHERE id = #{issueId}
            """)
    int mergeReport(@Param("issueId") UUID issueId, @Param("severity") int severity, @Param("now") Instant now);

    @Insert(
            """
            INSERT INTO issue_report
                (id, issue_id, reporter_user_id, source, type, description, severity,
                 location, occurred_at, created_at)
            VALUES
                (#{id}, #{issueId}, #{reporterUserId}, #{source}, #{type}, #{description}, #{severity},
                 ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography, #{occurredAt}, #{occurredAt})
            """)
    void insertReport(
            @Param("id") UUID id,
            @Param("issueId") UUID issueId,
            @Param("reporterUserId") UUID reporterUserId,
            @Param("source") String source,
            @Param("type") IssueType type,
            @Param("description") String description,
            @Param("severity") int severity,
            @Param("longitude") double longitude,
            @Param("latitude") double latitude,
            @Param("occurredAt") Instant occurredAt);

    @Select(
            """
            SELECT id, reporter_user_id, type, description, status, severity, report_count,
                   confirmation_count, rejection_count, confidence_score,
                   (severity * confidence_score / 10)::integer AS risk_score, version,
                   ST_X(location::geometry) AS longitude,
                   ST_Y(location::geometry) AS latitude,
                   NULL::double precision AS distance_meters,
                   last_reported_at, created_at, updated_at
            FROM accessibility_issue
            WHERE id = #{id}
            """)
    Optional<IssueRow> findById(UUID id);

    @Select(
            """
            SELECT id, reporter_user_id, type, description, status, severity, report_count,
                   confirmation_count, rejection_count, confidence_score,
                   (severity * confidence_score / 10)::integer AS risk_score, version,
                   ST_X(location::geometry) AS longitude,
                   ST_Y(location::geometry) AS latitude,
                   ST_Distance(location,
                       ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography) AS distance_meters,
                   last_reported_at, created_at, updated_at
            FROM accessibility_issue
            WHERE status IN ('PENDING', 'VERIFIED', 'PROCESSING')
              AND ST_DWithin(location,
                  ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography,
                  #{radiusMeters})
            ORDER BY risk_score DESC, distance_meters, last_reported_at DESC
            LIMIT #{limit}
            """)
    List<IssueRow> nearby(
            @Param("longitude") double longitude,
            @Param("latitude") double latitude,
            @Param("radiusMeters") int radiusMeters,
            @Param("limit") int limit);

    @Insert(
            """
            INSERT INTO issue_status_history
                (id, issue_id, from_status, to_status, changed_by, reason, changed_at)
            VALUES
                (#{id}, #{issueId}, #{fromStatus}, #{toStatus}, #{changedBy}, #{reason}, #{changedAt})
            """)
    void insertHistory(
            @Param("id") UUID id,
            @Param("issueId") UUID issueId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("changedBy") UUID changedBy,
            @Param("reason") String reason,
            @Param("changedAt") Instant changedAt);

    @Insert(
            """
            INSERT INTO issue_verification
                (id, issue_id, user_id, decision, note, created_at, updated_at)
            VALUES
                (#{id}, #{issueId}, #{userId}, #{decision}, #{note}, #{now}, #{now})
            ON CONFLICT (issue_id, user_id) DO UPDATE
            SET decision = EXCLUDED.decision, note = EXCLUDED.note, updated_at = EXCLUDED.updated_at
            """)
    void upsertVerification(
            @Param("id") UUID id,
            @Param("issueId") UUID issueId,
            @Param("userId") UUID userId,
            @Param("decision") String decision,
            @Param("note") String note,
            @Param("now") Instant now);

    @Select(
            """
            SELECT count(*) FILTER (WHERE decision = 'CONFIRM')::integer AS confirms,
                   count(*) FILTER (WHERE decision = 'REJECT')::integer AS rejects
            FROM issue_verification
            WHERE issue_id = #{issueId}
            """)
    VerificationCounts countVerifications(UUID issueId);

    @Update(
            """
            UPDATE accessibility_issue
            SET confirmation_count = #{confirms}, rejection_count = #{rejects},
                confidence_score = GREATEST(0, LEAST(100, 20 + report_count * 15 + #{confirms} * 20 - #{rejects} * 25)),
                updated_at = #{now}
            WHERE id = #{issueId}
            """)
    void updateTrust(
            @Param("issueId") UUID issueId,
            @Param("confirms") int confirms,
            @Param("rejects") int rejects,
            @Param("now") Instant now);

    @Select(
            """
            WITH route AS (
                SELECT ST_SetSRID(ST_GeomFromText(#{lineStringWkt}), 4326)::geography AS path
            )
            SELECT i.id AS issue_id, i.type, i.severity, i.confidence_score,
                   ST_Distance(i.location, route.path) AS distance_to_route_meters,
                   GREATEST(1, ROUND(i.severity * i.confidence_score / 10.0
                       * GREATEST(0.25, 1 - EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - i.last_reported_at)) / 2592000.0)
                       * (1 - ST_Distance(i.location, route.path) / #{corridorMeters})))::integer AS contribution
            FROM accessibility_issue i CROSS JOIN route
            WHERE i.status IN ('PENDING', 'VERIFIED', 'PROCESSING')
              AND i.confidence_score >= 20
              AND ST_DWithin(i.location, route.path, #{corridorMeters})
            ORDER BY contribution DESC, distance_to_route_meters
            LIMIT 100
            """)
    List<RouteRiskRow> findRouteRisks(
            @Param("lineStringWkt") String lineStringWkt, @Param("corridorMeters") int corridorMeters);

    @Select(
            """
            SELECT id, reporter_user_id, source, type, description, severity,
                   ST_X(location::geometry) AS longitude, ST_Y(location::geometry) AS latitude, occurred_at
            FROM issue_report
            WHERE issue_id = #{issueId}
            ORDER BY occurred_at DESC, id
            """)
    List<IssueReportRow> findReports(UUID issueId);

    @Update(
            """
            UPDATE accessibility_issue
            SET status = #{targetStatus}, version = version + 1, updated_at = #{now},
                processing_by = CASE WHEN #{targetStatus} = 'PROCESSING' THEN #{actorUserId} ELSE processing_by END,
                processing_at = CASE WHEN #{targetStatus} = 'PROCESSING' THEN #{now} ELSE processing_at END,
                resolved_at = CASE WHEN #{targetStatus} = 'RESOLVED' THEN #{now} ELSE resolved_at END,
                resolved_by = CASE WHEN #{targetStatus} = 'RESOLVED' THEN #{actorUserId} ELSE resolved_by END,
                closed_at = CASE WHEN #{targetStatus} = 'CLOSED' THEN #{now} ELSE closed_at END
            WHERE id = #{issueId} AND version = #{version} AND status = #{expectedStatus}
            """)
    int transitionStatus(
            @Param("issueId") UUID issueId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("version") int version,
            @Param("actorUserId") UUID actorUserId,
            @Param("now") Instant now);

    @Update(
            """
            UPDATE accessibility_issue
            SET status = #{status}, version = version + 1, updated_at = #{now}
            WHERE id = #{issueId} AND status IN ('PENDING', 'VERIFIED', 'REJECTED')
            """)
    int updateStatus(@Param("issueId") UUID issueId, @Param("status") String status, @Param("now") Instant now);

    @Select(
            """
            SELECT count(*)
            FROM media_asset
            WHERE id = #{mediaAssetId} AND owner_user_id = #{userId}
            """)
    int countOwnedMedia(@Param("mediaAssetId") UUID mediaAssetId, @Param("userId") UUID userId);

    @Insert(
            """
            INSERT INTO issue_evidence (id, issue_id, media_asset_id, submitted_by, created_at)
            VALUES (#{id}, #{issueId}, #{mediaAssetId}, #{userId}, #{now})
            ON CONFLICT (issue_id, media_asset_id) DO NOTHING
            """)
    int insertEvidence(
            @Param("id") UUID id,
            @Param("issueId") UUID issueId,
            @Param("mediaAssetId") UUID mediaAssetId,
            @Param("userId") UUID userId,
            @Param("now") Instant now);
}

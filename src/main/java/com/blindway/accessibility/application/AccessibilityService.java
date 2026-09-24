package com.blindway.accessibility.application;

import com.blindway.accessibility.RouteIssueAccess;
import com.blindway.accessibility.api.CreateIssueRequest;
import com.blindway.accessibility.api.IssueReportResponse;
import com.blindway.accessibility.api.IssueResponse;
import com.blindway.accessibility.api.RouteRiskRequest;
import com.blindway.accessibility.api.RouteRiskResponse;
import com.blindway.accessibility.api.TransitionIssueRequest;
import com.blindway.accessibility.api.VerificationRequest;
import com.blindway.accessibility.domain.IssueStatus;
import com.blindway.accessibility.infrastructure.AccessibilityMapper;
import com.blindway.accessibility.infrastructure.IssueRow;
import com.blindway.accessibility.infrastructure.VerificationCounts;
import com.blindway.common.api.ApiException;
import com.blindway.common.infrastructure.CacheConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccessibilityService implements RouteIssueAccess {

    private static final int DUPLICATE_RADIUS_METERS = 20;
    private static final long DUPLICATE_WINDOW_MINUTES = 30;
    private static final int DEFAULT_ROUTE_CORRIDOR_METERS = 20;

    private final AccessibilityMapper mapper;
    private final SpatialQueryGuard spatialQueryGuard;

    public AccessibilityService(AccessibilityMapper mapper, SpatialQueryGuard spatialQueryGuard) {
        this.mapper = mapper;
        this.spatialQueryGuard = spatialQueryGuard;
    }

    @Transactional
    @CacheEvict(
            cacheNames = {CacheConfig.NEARBY_ISSUES, CacheConfig.ROUTE_RISKS},
            allEntries = true)
    public IssueResponse create(UUID userId, CreateIssueRequest request) {
        Instant now = Instant.now();
        aggregationLockKeys(request).forEach(mapper::lockAggregationBucket);
        var duplicate = mapper.findDuplicateForUpdate(
                request.type(),
                request.longitude(),
                request.latitude(),
                DUPLICATE_RADIUS_METERS,
                now.minus(DUPLICATE_WINDOW_MINUTES, ChronoUnit.MINUTES));
        if (duplicate.isPresent()) {
            IssueRow existing = duplicate.orElseThrow();
            mapper.mergeReport(existing.id(), request.severity(), now);
            insertUserReport(existing.id(), userId, request, now);
            mapper.insertHistory(
                    UUID.randomUUID(), existing.id(), existing.status(), existing.status(), userId, "重复上报已聚合为新证据", now);
            return response(requireIssue(existing.id()));
        }

        UUID id = UUID.randomUUID();
        mapper.insertIssue(
                id,
                userId,
                request.type(),
                request.description().strip(),
                request.severity(),
                request.longitude(),
                request.latitude(),
                now);
        insertUserReport(id, userId, request, now);
        mapper.insertHistory(UUID.randomUUID(), id, null, "PENDING", userId, "问题首次上报", now);
        return mapper.findById(id).map(this::response).orElseThrow();
    }

    @Transactional(readOnly = true)
    @Cacheable(
            cacheNames = CacheConfig.NEARBY_ISSUES,
            key = "{#longitude, #latitude, #radiusMeters, #limit}",
            sync = true)
    public List<IssueResponse> nearby(double longitude, double latitude, int radiusMeters, int limit) {
        return spatialQueryGuard.execute("nearby", () -> {
            mapper.setLocalStatementTimeout(spatialQueryGuard.statementTimeout("nearby"));
            return mapper.nearby(longitude, latitude, radiusMeters, limit).stream()
                    .map(this::response)
                    .toList();
        });
    }

    @Transactional(readOnly = true)
    public IssueResponse get(UUID issueId) {
        return response(requireIssue(issueId));
    }

    @Transactional
    @CacheEvict(
            cacheNames = {CacheConfig.NEARBY_ISSUES, CacheConfig.ROUTE_RISKS},
            allEntries = true)
    public void verify(UUID userId, UUID issueId, VerificationRequest request) {
        IssueRow issue = mapper.findByIdForUpdate(issueId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ISSUE_NOT_FOUND", "无障碍问题不存在"));
        if (!List.of("PENDING", "VERIFIED", "REJECTED", "REOPENED").contains(issue.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ISSUE_NOT_VOTABLE", "当前问题状态不允许投票");
        }
        Instant now = Instant.now();
        mapper.upsertVerification(
                UUID.randomUUID(),
                issueId,
                userId,
                request.decision().name(),
                request.riskLevel() == null ? null : request.riskLevel().name(),
                request.note(),
                now);
        VerificationCounts counts = mapper.countVerifications(issueId);
        String riskLevel = mapper.winningRiskLevel(issueId).orElse(null);
        mapper.updateTrust(issueId, counts.confirms(), counts.rejects(), riskLevel, now);
        String nextStatus = counts.confirms() >= 2 && counts.confirms() > counts.rejects() && riskLevel != null
                ? "VERIFIED"
                : counts.rejects() >= 2 && counts.rejects() >= counts.confirms() ? "REJECTED" : "PENDING";
        if (!nextStatus.equals(issue.status())) {
            if (mapper.updateStatus(issueId, nextStatus, now) != 1) {
                throw new ApiException(HttpStatus.CONFLICT, "ISSUE_VERSION_CONFLICT", "问题状态已变化，请重试");
            }
            mapper.insertHistory(UUID.randomUUID(), issueId, issue.status(), nextStatus, userId, "社区核验结果", now);
        }
    }

    @Transactional
    public void attachEvidence(UUID userId, UUID issueId, UUID mediaAssetId) {
        requireIssue(issueId);
        if (mapper.countOwnedMedia(mediaAssetId, userId) != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "图片不存在或不属于当前用户");
        }
        mapper.insertEvidence(UUID.randomUUID(), issueId, mediaAssetId, userId, Instant.now());
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.ROUTE_RISKS, key = "#request", sync = true)
    public RouteRiskResponse assessRoute(RouteRiskRequest request) {
        int corridorMeters =
                request.corridorMeters() == null ? DEFAULT_ROUTE_CORRIDOR_METERS : request.corridorMeters();
        Inspection inspection = inspect(
                request.points().stream()
                        .map(point -> new Point(point.longitude(), point.latitude()))
                        .toList(),
                corridorMeters);
        return new RouteRiskResponse(
                inspection.highCount(),
                inspection.mediumCount(),
                inspection.lowCount(),
                inspection.corridorMeters(),
                inspection.issues().stream()
                        .map(issue -> new RouteRiskResponse.RiskIssue(
                                issue.issueId(),
                                issue.type(),
                                issue.riskLevel(),
                                issue.longitude(),
                                issue.latitude(),
                                issue.nearestLongitude(),
                                issue.nearestLatitude(),
                                issue.distanceToRouteMeters()))
                        .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Inspection inspect(List<Point> points, int corridorMeters) {
        if (points.size() < 2 || points.size() > 5000 || corridorMeters < 5 || corridorMeters > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROUTE", "路线点数或走廊宽度无效");
        }
        String lineString = "LINESTRING("
                + points.stream()
                        .map(point -> point.longitude() + " " + point.latitude())
                        .collect(java.util.stream.Collectors.joining(","))
                + ")";
        return spatialQueryGuard.execute("route", () -> {
            mapper.setLocalStatementTimeout(spatialQueryGuard.statementTimeout("route"));
            var rows = mapper.findRouteRisks(lineString, corridorMeters);
            var issues = rows.stream()
                    .map(row -> new Issue(
                            row.issueId(),
                            row.type().name(),
                            row.riskLevel(),
                            row.longitude(),
                            row.latitude(),
                            row.nearestLongitude(),
                            row.nearestLatitude(),
                            row.distanceToRouteMeters()))
                    .toList();
            return new Inspection(
                    (int) rows.stream()
                            .filter(row -> "HIGH".equals(row.riskLevel()))
                            .count(),
                    (int) rows.stream()
                            .filter(row -> "MEDIUM".equals(row.riskLevel()))
                            .count(),
                    (int) rows.stream()
                            .filter(row -> "LOW".equals(row.riskLevel()))
                            .count(),
                    corridorMeters,
                    issues);
        });
    }

    @Transactional(readOnly = true)
    public List<IssueReportResponse> reports(UUID issueId) {
        requireIssue(issueId);
        return mapper.findReports(issueId).stream()
                .map(row -> new IssueReportResponse(
                        row.id(),
                        row.reporterUserId(),
                        row.source(),
                        row.type(),
                        row.description(),
                        row.severity(),
                        row.longitude(),
                        row.latitude(),
                        row.occurredAt()))
                .toList();
    }

    @Transactional
    @CacheEvict(
            cacheNames = {CacheConfig.NEARBY_ISSUES, CacheConfig.ROUTE_RISKS},
            allEntries = true)
    public IssueResponse transition(UUID actorUserId, UUID issueId, TransitionIssueRequest request) {
        IssueRow issue = requireIssue(issueId);
        IssueStatus current = IssueStatus.valueOf(issue.status());
        if (!current.canTransitionTo(request.targetStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_ISSUE_TRANSITION", "当前状态不允许该转换");
        }
        Instant now = Instant.now();
        if (mapper.transitionStatus(
                        issueId, current.name(), request.targetStatus().name(), request.version(), actorUserId, now)
                != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "ISSUE_VERSION_CONFLICT", "问题状态已被其他操作更新，请刷新后重试");
        }
        mapper.insertHistory(
                UUID.randomUUID(),
                issueId,
                current.name(),
                request.targetStatus().name(),
                actorUserId,
                request.reason().strip(),
                now);
        return response(requireIssue(issueId));
    }

    private List<String> aggregationLockKeys(CreateIssueRequest request) {
        long longitudeCell = (long) Math.floor((request.longitude() + 180.0) * 2000);
        long latitudeCell = (long) Math.floor((request.latitude() + 90.0) * 2000);
        java.util.ArrayList<String> keys = new java.util.ArrayList<>(9);
        for (long longitude = longitudeCell - 1; longitude <= longitudeCell + 1; longitude++) {
            for (long latitude = latitudeCell - 1; latitude <= latitudeCell + 1; latitude++) {
                keys.add(request.type().name() + ':' + longitude + ':' + latitude);
            }
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private void insertUserReport(UUID issueId, UUID userId, CreateIssueRequest request, Instant occurredAt) {
        mapper.insertReport(
                UUID.randomUUID(),
                issueId,
                userId,
                "USER",
                request.type(),
                request.description().strip(),
                request.severity(),
                request.longitude(),
                request.latitude(),
                occurredAt);
    }

    private IssueRow requireIssue(UUID issueId) {
        return mapper.findById(issueId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ISSUE_NOT_FOUND", "无障碍问题不存在"));
    }

    private IssueResponse response(IssueRow row) {
        return new IssueResponse(
                row.id(),
                row.type(),
                row.description(),
                row.status(),
                row.severity(),
                row.verifiedRiskLevel(),
                row.reportCount(),
                row.confirmationCount(),
                row.rejectionCount(),
                row.confidenceScore(),
                row.riskScore(),
                row.version(),
                row.longitude(),
                row.latitude(),
                row.distanceMeters(),
                row.lastReportedAt(),
                row.createdAt());
    }
}

package com.blindway.accessibility.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.blindway.accessibility.api.CreateIssueRequest;
import com.blindway.accessibility.api.RouteRiskRequest;
import com.blindway.accessibility.api.TransitionIssueRequest;
import com.blindway.accessibility.api.VerificationRequest;
import com.blindway.accessibility.domain.IssueStatus;
import com.blindway.accessibility.domain.IssueType;
import com.blindway.accessibility.infrastructure.AccessibilityMapper;
import com.blindway.accessibility.infrastructure.IssueRow;
import com.blindway.accessibility.infrastructure.RouteRiskRow;
import com.blindway.accessibility.infrastructure.VerificationCounts;
import com.blindway.common.api.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccessibilityServiceTest {

    @Mock
    private AccessibilityMapper mapper;

    @Test
    void mergesNearbyRecentReportInsteadOfCreatingDuplicateIssue() {
        UUID issueId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        IssueRow existing = issue(issueId, "PENDING");
        when(mapper.findDuplicateForUpdate(
                        eq(IssueType.TACTILE_PAVING_DAMAGED),
                        eq(116.397128),
                        eq(39.916527),
                        eq(20),
                        any(Instant.class)))
                .thenReturn(Optional.of(existing));
        when(mapper.findById(issueId)).thenReturn(Optional.of(existing));
        AccessibilityService service = service();

        service.create(
                userId, new CreateIssueRequest(IssueType.TACTILE_PAVING_DAMAGED, "同一处盲道破损", 4, 116.397128, 39.916527));

        verify(mapper, times(9)).lockAggregationBucket(any(String.class));
        verify(mapper).mergeReport(eq(issueId), eq(4), any(Instant.class));
        verify(mapper)
                .insertReport(
                        any(),
                        eq(issueId),
                        eq(userId),
                        eq("USER"),
                        eq(IssueType.TACTILE_PAVING_DAMAGED),
                        eq("同一处盲道破损"),
                        eq(4),
                        eq(116.397128),
                        eq(39.916527),
                        any());
        verify(mapper, never()).insertIssue(any(), any(), any(), any(), anyInt(), anyDouble(), anyDouble(), any());
    }

    @Test
    void scoresRouteFromNearbyIssueContributions() {
        when(mapper.findRouteRisks("LINESTRING(116.39 39.91,116.4 39.92)", 20))
                .thenReturn(List.of(
                        new RouteRiskRow(UUID.randomUUID(), IssueType.CONSTRUCTION, 5, 80, 3.5, 32),
                        new RouteRiskRow(UUID.randomUUID(), IssueType.TACTILE_PAVING_DAMAGED, 3, 60, 12.0, 11)));
        AccessibilityService service = service();

        var result = service.assessRoute(new RouteRiskRequest(
                List.of(new RouteRiskRequest.Point(116.39, 39.91), new RouteRiskRequest.Point(116.4, 39.92)), null));

        org.assertj.core.api.Assertions.assertThat(result.riskScore()).isEqualTo(43);
        org.assertj.core.api.Assertions.assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        org.assertj.core.api.Assertions.assertThat(result.issueCount()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(result.corridorMeters()).isEqualTo(20);
    }

    @Test
    void keepsExplicitRouteCorridor() {
        when(mapper.findRouteRisks("LINESTRING(116.39 39.91,116.4 39.92)", 30)).thenReturn(List.of());
        AccessibilityService service = service();

        var result = service.assessRoute(new RouteRiskRequest(
                List.of(new RouteRiskRequest.Point(116.39, 39.91), new RouteRiskRequest.Point(116.4, 39.92)), 30));

        org.assertj.core.api.Assertions.assertThat(result.corridorMeters()).isEqualTo(30);
        verify(mapper).findRouteRisks("LINESTRING(116.39 39.91,116.4 39.92)", 30);
    }

    @Test
    void transitionsVerifiedIssueWithOptimisticLock() {
        UUID issueId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        IssueRow verified = issue(issueId, "VERIFIED");
        IssueRow processing = issue(issueId, "PROCESSING");
        when(mapper.findById(issueId)).thenReturn(Optional.of(verified), Optional.of(processing));
        when(mapper.transitionStatus(
                        eq(issueId), eq("VERIFIED"), eq("PROCESSING"), eq(0), eq(adminId), any(Instant.class)))
                .thenReturn(1);
        AccessibilityService service = service();

        service.transition(adminId, issueId, new TransitionIssueRequest(IssueStatus.PROCESSING, "治理人员已受理", 0));

        verify(mapper)
                .transitionStatus(
                        eq(issueId), eq("VERIFIED"), eq("PROCESSING"), eq(0), eq(adminId), any(Instant.class));
    }

    @Test
    void rejectsIllegalWorkflowTransition() {
        UUID issueId = UUID.randomUUID();
        when(mapper.findById(issueId)).thenReturn(Optional.of(issue(issueId, "PENDING")));
        AccessibilityService service = service();

        assertThatThrownBy(() -> service.transition(
                        UUID.randomUUID(), issueId, new TransitionIssueRequest(IssueStatus.RESOLVED, "尝试跳过核验", 0)))
                .isInstanceOf(ApiException.class)
                .hasMessage("当前状态不允许该转换");
    }

    @Test
    void verifiesIssueAfterTwoConfirmations() {
        UUID issueId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(mapper.findById(issueId)).thenReturn(Optional.of(issue(issueId, "PENDING")));
        when(mapper.countVerifications(issueId)).thenReturn(new VerificationCounts(2, 0));
        AccessibilityService service = service();

        service.verify(userId, issueId, new VerificationRequest(VerificationRequest.Decision.CONFIRM, "现场仍然存在"));

        verify(mapper).updateStatus(eq(issueId), eq("VERIFIED"), any(Instant.class));
        verify(mapper)
                .insertHistory(
                        any(UUID.class),
                        eq(issueId),
                        eq("PENDING"),
                        eq("VERIFIED"),
                        eq(userId),
                        eq("社区核验结果"),
                        any(Instant.class));
    }

    @Test
    void doesNotChangeStateWithOnlyOneConfirmation() {
        UUID issueId = UUID.randomUUID();
        when(mapper.findById(issueId)).thenReturn(Optional.of(issue(issueId, "PENDING")));
        when(mapper.countVerifications(issueId)).thenReturn(new VerificationCounts(1, 0));
        AccessibilityService service = service();

        service.verify(UUID.randomUUID(), issueId, new VerificationRequest(VerificationRequest.Decision.CONFIRM, null));

        verify(mapper, never()).updateStatus(any(), any(), any());
    }

    @Test
    void rejectsVerificationForResolvedIssue() {
        UUID issueId = UUID.randomUUID();
        when(mapper.findById(issueId)).thenReturn(Optional.of(issue(issueId, "RESOLVED")));
        AccessibilityService service = service();

        assertThatThrownBy(() -> service.verify(
                        UUID.randomUUID(),
                        issueId,
                        new VerificationRequest(VerificationRequest.Decision.CONFIRM, null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("问题已解决");
    }

    @Test
    void refusesEvidenceOwnedByAnotherUser() {
        UUID issueId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        when(mapper.findById(issueId)).thenReturn(Optional.of(issue(issueId, "PENDING")));
        when(mapper.countOwnedMedia(mediaId, userId)).thenReturn(0);
        AccessibilityService service = service();

        assertThatThrownBy(() -> service.attachEvidence(userId, issueId, mediaId))
                .isInstanceOf(ApiException.class)
                .hasMessage("图片不存在或不属于当前用户");

        verify(mapper, never()).insertEvidence(any(), any(), any(), any(), any());
    }

    private IssueRow issue(UUID issueId, String status) {
        return new IssueRow(
                issueId,
                UUID.randomUUID(),
                IssueType.TACTILE_PAVING_DAMAGED,
                "盲道破损",
                status,
                3,
                1,
                0,
                0,
                20,
                6,
                0,
                116.397128,
                39.916527,
                null,
                Instant.parse("2026-08-04T08:30:00Z"),
                Instant.parse("2026-08-04T08:30:00Z"),
                Instant.parse("2026-08-04T08:30:00Z"));
    }

    private AccessibilityService service() {
        return new AccessibilityService(
                mapper,
                new SpatialQueryGuard(
                        new SimpleMeterRegistry(),
                        5,
                        Duration.ofMillis(50),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1500)));
    }
}

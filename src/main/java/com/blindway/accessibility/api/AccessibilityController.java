package com.blindway.accessibility.api;

import com.blindway.accessibility.application.AccessibilityService;
import com.blindway.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/accessibility-issues")
public class AccessibilityController {

    private final AccessibilityService service;

    public AccessibilityController(AccessibilityService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    IssueResponse create(@Valid @RequestBody CreateIssueRequest request, @AuthenticationPrincipal Jwt jwt) {
        return service.create(CurrentUser.id(jwt), request);
    }

    @GetMapping
    List<IssueResponse> nearby(
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude,
            @RequestParam(defaultValue = "500") @Min(10) @Max(5000) int radiusMeters,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.nearby(longitude, latitude, radiusMeters, limit);
    }

    @GetMapping("/{issueId}")
    IssueResponse get(@PathVariable UUID issueId) {
        return service.get(issueId);
    }

    @GetMapping("/{issueId}/reports")
    List<IssueReportResponse> reports(@PathVariable UUID issueId) {
        return service.reports(issueId);
    }

    @PostMapping("/{issueId}/verifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verify(
            @PathVariable UUID issueId,
            @Valid @RequestBody VerificationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        service.verify(CurrentUser.id(jwt), issueId, request);
    }

    @PostMapping("/{issueId}/evidence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void attachEvidence(
            @PathVariable UUID issueId, @Valid @RequestBody EvidenceRequest request, @AuthenticationPrincipal Jwt jwt) {
        service.attachEvidence(CurrentUser.id(jwt), issueId, request.mediaAssetId());
    }

    @PostMapping("/route-risk-assessments")
    RouteRiskResponse assessRoute(@Valid @RequestBody RouteRiskRequest request) {
        return service.assessRoute(request);
    }

    @PostMapping("/{issueId}/transitions")
    @PreAuthorize("hasAnyRole('VOLUNTEER', 'ADMIN')")
    IssueResponse transition(
            @PathVariable UUID issueId,
            @Valid @RequestBody TransitionIssueRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.transition(CurrentUser.id(jwt), issueId, request);
    }
}

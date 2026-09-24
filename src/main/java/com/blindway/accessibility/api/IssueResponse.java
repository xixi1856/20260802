package com.blindway.accessibility.api;

import com.blindway.accessibility.domain.IssueType;
import java.time.Instant;
import java.util.UUID;

public record IssueResponse(
        UUID id,
        IssueType type,
        String description,
        String status,
        int severity,
        String verifiedRiskLevel,
        int reportCount,
        int confirmationCount,
        int rejectionCount,
        int confidenceScore,
        int riskScore,
        int version,
        double longitude,
        double latitude,
        Double distanceMeters,
        Instant lastReportedAt,
        Instant createdAt) {}

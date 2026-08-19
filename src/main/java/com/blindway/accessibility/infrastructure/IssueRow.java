package com.blindway.accessibility.infrastructure;

import com.blindway.accessibility.domain.IssueType;
import java.time.Instant;
import java.util.UUID;

public record IssueRow(
        UUID id,
        UUID reporterUserId,
        IssueType type,
        String description,
        String status,
        int severity,
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
        Instant createdAt,
        Instant updatedAt) {}

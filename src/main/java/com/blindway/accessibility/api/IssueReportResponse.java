package com.blindway.accessibility.api;

import com.blindway.accessibility.domain.IssueType;
import java.time.Instant;
import java.util.UUID;

public record IssueReportResponse(
        UUID id,
        UUID reporterUserId,
        String source,
        IssueType type,
        String description,
        int severity,
        double longitude,
        double latitude,
        Instant occurredAt) {}

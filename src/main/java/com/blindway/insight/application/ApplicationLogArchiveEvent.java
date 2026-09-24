package com.blindway.insight.application;

import java.time.Instant;
import java.util.UUID;

public record ApplicationLogArchiveEvent(
        UUID logId,
        Instant occurredAt,
        String level,
        String logger,
        String message,
        String service,
        String instance,
        String traceId) {}

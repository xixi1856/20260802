package com.blindway.identity.infrastructure;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRow(
        UUID id,
        UUID userId,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt,
        UUID replacedBy,
        Instant createdAt) {}

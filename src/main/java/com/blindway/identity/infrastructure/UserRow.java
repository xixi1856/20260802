package com.blindway.identity.infrastructure;

import com.blindway.identity.domain.UserRole;
import java.time.Instant;
import java.util.UUID;

public record UserRow(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        UserRole role,
        String status,
        Instant createdAt,
        Instant updatedAt) {}

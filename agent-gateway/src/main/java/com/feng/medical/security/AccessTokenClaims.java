package com.feng.medical.security;

import java.time.Instant;
import java.util.UUID;

public record AccessTokenClaims(
        UUID userId,
        String username,
        UserRole role,
        int authVersion,
        Instant issuedAt,
        Instant expiresAt) {
}

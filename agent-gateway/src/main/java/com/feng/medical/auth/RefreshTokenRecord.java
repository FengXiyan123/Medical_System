package com.feng.medical.auth;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRecord(
        UUID id,
        UUID userId,
        String tokenHash,
        Instant expiresAt,
        Instant revokedAt,
        UUID replacedById) {

    public boolean isUsableAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}

package com.feng.medical.auth;

import com.feng.medical.security.UserRole;
import java.time.Instant;
import java.util.UUID;

public record AuthTokens(
        String accessToken,
        String refreshToken,
        UUID userId,
        String username,
        UserRole role,
        Instant accessTokenExpiresAt) {
}

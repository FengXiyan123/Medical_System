package com.feng.medical.auth;

import com.feng.medical.security.UserRole;
import java.time.Instant;
import java.util.UUID;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        Instant accessTokenExpiresAt,
        UserResponse user) {

    static TokenResponse from(AuthTokens tokens) {
        return new TokenResponse(
                tokens.accessToken(),
                tokens.refreshToken(),
                "Bearer",
                tokens.accessTokenExpiresAt(),
                new UserResponse(tokens.userId(), tokens.username(), tokens.role()));
    }

    public record UserResponse(UUID id, String username, UserRole role) {
    }
}

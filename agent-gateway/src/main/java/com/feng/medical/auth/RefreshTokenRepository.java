package com.feng.medical.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    Optional<RefreshTokenRecord> findByHash(String tokenHash);

    void save(RefreshTokenRecord token);

    boolean revokeIfActive(UUID id, Instant revokedAt, UUID replacementId);
}

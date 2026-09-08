package com.feng.medical.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {

    private final JdbcClient jdbc;

    public JdbcRefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RefreshTokenRecord> findByHash(String tokenHash) {
        return jdbc.sql("""
                        SELECT id, user_id, token_hash, expires_at, revoked_at, replaced_by_id
                        FROM refresh_token WHERE token_hash = :tokenHash
                        """)
                .param("tokenHash", tokenHash)
                .query(this::mapToken)
                .optional();
    }

    @Override
    public void save(RefreshTokenRecord token) {
        jdbc.sql("""
                        INSERT INTO refresh_token (id, user_id, token_hash, expires_at, revoked_at, replaced_by_id)
                        VALUES (:id, :userId, :tokenHash, :expiresAt, :revokedAt, :replacedById)
                        """)
                .param("id", token.id().toString())
                .param("userId", token.userId().toString())
                .param("tokenHash", token.tokenHash())
                .param("expiresAt", token.expiresAt())
                .param("revokedAt", token.revokedAt())
                .param("replacedById", token.replacedById() == null ? null : token.replacedById().toString())
                .update();
    }

    @Override
    public boolean revokeIfActive(UUID id, Instant revokedAt, UUID replacementId) {
        return jdbc.sql("""
                        UPDATE refresh_token
                        SET revoked_at = :revokedAt, replaced_by_id = :replacementId
                        WHERE id = :id AND revoked_at IS NULL
                        """)
                .param("id", id.toString())
                .param("revokedAt", revokedAt)
                .param("replacementId", replacementId == null ? null : replacementId.toString())
                .update() == 1;
    }

    private RefreshTokenRecord mapToken(ResultSet resultSet, int rowNum) throws SQLException {
        String replacementId = resultSet.getString("replaced_by_id");
        return new RefreshTokenRecord(
                UUID.fromString(resultSet.getString("id")),
                UUID.fromString(resultSet.getString("user_id")),
                resultSet.getString("token_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getTimestamp("revoked_at") == null ? null : resultSet.getTimestamp("revoked_at").toInstant(),
                replacementId == null ? null : UUID.fromString(replacementId));
    }
}

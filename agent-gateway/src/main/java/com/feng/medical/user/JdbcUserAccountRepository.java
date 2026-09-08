package com.feng.medical.user;

import com.feng.medical.security.UserRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserAccountRepository implements UserAccountRepository {
    private final JdbcClient jdbc;
    public JdbcUserAccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.sql("SELECT id, username, password_hash, role, status, auth_version FROM app_user WHERE username = :username")
                .param("username", username).query(this::mapAccount).optional();
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql("SELECT id, username, password_hash, role, status, auth_version FROM app_user WHERE id = :id")
                .param("id", id.toString()).query(this::mapAccount).optional();
    }

    private UserAccount mapAccount(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new UserAccount(
                UUID.fromString(rs.getString("id")),
                rs.getString("username"),
                rs.getString("password_hash"),
                UserRole.valueOf(rs.getString("role")),
                "ACTIVE".equals(rs.getString("status")),
                rs.getInt("auth_version"));
    }
}

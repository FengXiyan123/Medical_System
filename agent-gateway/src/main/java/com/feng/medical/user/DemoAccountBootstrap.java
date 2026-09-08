package com.feng.medical.user;

import com.feng.medical.security.UserRole;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates local teaching accounts only when explicitly enabled through environment configuration. */
@Component
class DemoAccountBootstrap implements ApplicationRunner {
    private final DemoBootstrapProperties properties;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;

    DemoAccountBootstrap(DemoBootstrapProperties properties, JdbcClient jdbc, PasswordEncoder passwords) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) return;
        createIfAbsent(properties.adminUsername(), properties.adminPassword(), "演示管理员", UserRole.ADMIN);
        createIfAbsent(properties.userUsername(), properties.userPassword(), "演示用户", UserRole.USER);
    }

    private void createIfAbsent(String username, String password, String displayName, UserRole role) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException("演示账号已启用，但用户名或密码未配置");
        }
        Long existing = jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username = :username")
                .param("username", username).query(Long.class).single();
        if (existing != null && existing > 0) return;
        jdbc.sql("INSERT INTO app_user (id, username, display_name, password_hash, role, status) VALUES (:id, :username, :displayName, :passwordHash, :role, 'ACTIVE')")
                .param("id", UUID.randomUUID().toString())
                .param("username", username)
                .param("displayName", displayName)
                .param("passwordHash", passwords.encode(password))
                .param("role", role.name())
                .update();
    }
}

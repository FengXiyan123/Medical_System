package com.feng.medical.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Local-demo credentials are supplied by the environment and are never persisted in source. */
@ConfigurationProperties(prefix = "app.demo-bootstrap")
public record DemoBootstrapProperties(
        boolean enabled,
        String adminUsername,
        String adminPassword,
        String userUsername,
        String userPassword) {
}

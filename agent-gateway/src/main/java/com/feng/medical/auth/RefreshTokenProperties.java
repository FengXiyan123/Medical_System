package com.feng.medical.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.refresh-token")
public record RefreshTokenProperties(long ttlSeconds) {
}

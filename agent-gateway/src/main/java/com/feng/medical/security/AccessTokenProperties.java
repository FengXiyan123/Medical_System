package com.feng.medical.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.access-token")
public record AccessTokenProperties(String secret, long ttlSeconds) {
}

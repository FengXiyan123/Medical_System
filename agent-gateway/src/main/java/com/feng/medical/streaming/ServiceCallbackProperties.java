package com.feng.medical.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.service-callback")
public record ServiceCallbackProperties(String secret) {
}

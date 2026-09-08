package com.feng.medical.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.agent-core")
public record AgentCoreKnowledgeProperties(String baseUrl) {
    public AgentCoreKnowledgeProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("agent core base URL must not be blank");
        }
    }
}

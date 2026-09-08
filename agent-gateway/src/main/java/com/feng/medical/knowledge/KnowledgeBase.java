package com.feng.medical.knowledge;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeBase(UUID id, String name, String description, KnowledgeBaseStatus status,
                            KnowledgeAccessScope accessScope, UUID createdBy, Instant createdAt, Instant updatedAt) {
}

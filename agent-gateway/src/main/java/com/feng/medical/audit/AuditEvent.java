package com.feng.medical.audit;

import java.time.Instant;
import java.util.UUID;
public record AuditEvent(UUID id, UUID actorId, String action, String targetType, UUID targetId,
                         UUID traceId, String redactedDiff, Instant createdAt) { }

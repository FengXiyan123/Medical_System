package com.feng.medical.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface AuditEventRepository {
    void append(AuditEvent event);
    List<AuditEvent> find(UUID actorId, String action, Instant from, Instant to, int limit);
}

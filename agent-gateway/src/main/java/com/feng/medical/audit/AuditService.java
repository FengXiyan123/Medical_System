package com.feng.medical.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final Consumer<AuditEvent> writer;
    private final Supplier<Instant> now;
    @Autowired
    public AuditService(AuditEventRepository repository, Clock clock) { this(repository::append, clock::instant); }
    AuditService(Consumer<AuditEvent> writer, Supplier<Instant> now) { this.writer = writer; this.now = now; }
    public void record(UUID actorId, String action, String targetType, UUID targetId, UUID traceId, String diff) {
        writer.accept(new AuditEvent(UUID.randomUUID(), actorId, action, targetType, targetId, traceId, redact(diff), now.get()));
    }
    /** Audit retains the fact and field names, never private source text or passwords. */
    static String redact(String diff) {
        if (diff == null || diff.isBlank()) return "{}";
        return diff.replaceAll("(?s):\\s*\"(?:\\\\.|[^\"])*\"", ":\"[REDACTED]\"")
                .replaceAll("(?i)(password|secret|token|content)\\s*[:=]\\s*[^,}\\s]+", "$1=[REDACTED]");
    }
}

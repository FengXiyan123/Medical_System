package com.feng.medical.conversation;

import java.time.Instant;
import java.util.UUID;

public record AgentRun(UUID id, UUID traceId, UUID userId, UUID conversationId, RunMode mode,
                       RunStatus status, String question, String idempotencyKey, String requestHash,
                       UUID requestMessageId, UUID regenerationOfRunId, Instant createdAt) {
    public AgentRun withStatus(RunStatus replacement) {
        return new AgentRun(id, traceId, userId, conversationId, mode, replacement, question,
                idempotencyKey, requestHash, requestMessageId, regenerationOfRunId, createdAt);
    }
}

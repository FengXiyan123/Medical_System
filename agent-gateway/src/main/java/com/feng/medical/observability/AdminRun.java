package com.feng.medical.observability;

import com.feng.medical.conversation.RunMode;
import com.feng.medical.conversation.RunStatus;
import java.time.Instant;
import java.util.UUID;

public record AdminRun(UUID id, UUID traceId, UUID userId, UUID conversationId, RunMode mode, RunStatus status,
                       String routeName, String finishReason, Instant createdAt, Instant completedAt) { }

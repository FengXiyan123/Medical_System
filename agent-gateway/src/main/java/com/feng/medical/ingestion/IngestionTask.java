package com.feng.medical.ingestion;

import java.time.Instant;
import java.util.UUID;

public record IngestionTask(UUID id, UUID documentId, IngestionTaskStage stage, IngestionTaskStatus status,
                            int attemptNo, long fencingToken, int progress, Instant createdAt) {
    public static IngestionTask queued(UUID documentId, Instant now) {
        return new IngestionTask(UUID.randomUUID(), documentId, IngestionTaskStage.PARSE, IngestionTaskStatus.QUEUED, 0, 0, 0, now);
    }
}

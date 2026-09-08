package com.feng.medical.ingestion;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, String aggregateType, UUID aggregateId, String eventType, String payload, Instant createdAt) {
}

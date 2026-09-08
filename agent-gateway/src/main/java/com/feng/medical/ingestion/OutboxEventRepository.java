package com.feng.medical.ingestion;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository {
    List<OutboxEvent> findPendingIngestionEvents(int limit);
    void markDispatched(UUID eventId, String streamId);
    void recordFailure(UUID eventId, String reason);
}

package com.feng.medical.ingestion;

import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OutboxDispatcher {
    private final OutboxEventRepository events; private final IngestionEventPublisher publisher; private final int batchSize;
    @Autowired
    public OutboxDispatcher(OutboxEventRepository events, IngestionEventPublisher publisher) { this(events, publisher, 50); }
    OutboxDispatcher(OutboxEventRepository events, IngestionEventPublisher publisher, int batchSize) {
        this.events = events; this.publisher = publisher; this.batchSize = batchSize;
    }
    @Scheduled(fixedDelayString = "${app.ingestion.outbox-delay-ms:1000}")
    public int dispatchPending() {
        List<OutboxEvent> pending = events.findPendingIngestionEvents(batchSize); int success = 0;
        for (OutboxEvent event : pending) {
            try { events.markDispatched(event.id(), publisher.publish(event)); success++; }
            catch (RuntimeException exception) { events.recordFailure(event.id(), exception.getMessage()); }
        }
        return success;
    }
}

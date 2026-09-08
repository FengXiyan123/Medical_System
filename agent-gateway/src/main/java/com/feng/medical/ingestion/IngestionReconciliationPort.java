package com.feng.medical.ingestion;

import java.time.Instant;

/** MySQL is authoritative for recovery; Redis only carries a duplicate-safe delivery hint. */
public interface IngestionReconciliationPort {
    int requeueExpiredLeases(Instant now, int maxAttempts);
    int failExpiredLeasesAtAttemptLimit(Instant now, int maxAttempts);
    int resetDispatchedEventsForQueuedTasks();
    int dispatchPendingEvents();
}

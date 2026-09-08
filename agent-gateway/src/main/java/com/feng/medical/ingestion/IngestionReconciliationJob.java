package com.feng.medical.ingestion;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reconciles expired ingestion leases from MySQL. It never trusts a Redis
 * pending list as the source of truth and each requeue advances the fence in
 * the same SQL update, preventing an old worker from committing afterward.
 */
@Service
public class IngestionReconciliationJob {
    private final IngestionReconciliationPort port;
    private final int maxAttempts;

    @Autowired
    public IngestionReconciliationJob(IngestionReconciliationPort port) { this(port, 3); }
    IngestionReconciliationJob(IngestionReconciliationPort port, int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts 必须大于零");
        this.port = port; this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.reconciliation-delay-ms:30000}")
    public IngestionRecoveryReport scheduledReconcile() { return reconcileExpiredLeases(Instant.now()); }

    public IngestionRecoveryReport reconcileExpiredLeases(Instant now) {
        int requeued = port.requeueExpiredLeases(now, maxAttempts);
        int failed = port.failExpiredLeasesAtAttemptLimit(now, maxAttempts);
        int republished = requeued == 0 ? 0 : port.dispatchPendingEvents();
        return new IngestionRecoveryReport(requeued, failed, republished);
    }

    /** Invoke after a confirmed Redis data loss; ordinary retries use the outbox dispatcher. */
    public int restoreAfterRedisLoss() { return port.resetDispatchedEventsForQueuedTasks(); }
}

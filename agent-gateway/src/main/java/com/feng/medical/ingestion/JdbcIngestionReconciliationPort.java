package com.feng.medical.ingestion;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIngestionReconciliationPort implements IngestionReconciliationPort {
    private final JdbcClient jdbc;
    private final OutboxDispatcher dispatcher;

    JdbcIngestionReconciliationPort(JdbcClient jdbc, OutboxDispatcher dispatcher) {
        this.jdbc = jdbc; this.dispatcher = dispatcher;
    }

    @Override public int requeueExpiredLeases(Instant now, int maxAttempts) {
        return jdbc.sql("UPDATE ingest_job SET status = 'QUEUED', attempt_no = attempt_no + 1, "
                        + "fencing_token = fencing_token + 1, lease_owner = NULL, lease_until = NULL, "
                        + "next_retry_at = NULL, error_code = 'LEASE_EXPIRED_RECOVERED' "
                        + "WHERE status = 'RUNNING' AND lease_until < :now AND attempt_no < :maxAttempts")
                .param("now", now).param("maxAttempts", maxAttempts).update();
    }

    @Override public int failExpiredLeasesAtAttemptLimit(Instant now, int maxAttempts) {
        return jdbc.sql("UPDATE ingest_job SET status = 'FAILED', lease_owner = NULL, lease_until = NULL, "
                        + "error_code = 'LEASE_EXPIRED_RETRY_EXHAUSTED' "
                        + "WHERE status = 'RUNNING' AND lease_until < :now AND attempt_no >= :maxAttempts")
                .param("now", now).param("maxAttempts", maxAttempts).update();
    }

    @Override public int resetDispatchedEventsForQueuedTasks() {
        return jdbc.sql("UPDATE outbox_event event JOIN ingest_job job "
                        + "ON JSON_UNQUOTE(JSON_EXTRACT(event.payload, '$.taskId')) = job.id "
                        + "SET event.dispatched_at = NULL, event.stream_id = NULL, event.last_error = 'REDIS_HISTORY_RESTORED' "
                        + "WHERE event.event_type = 'DOCUMENT_INGESTION_QUEUED' AND job.status = 'QUEUED'")
                .update();
    }

    @Override public int dispatchPendingEvents() { return dispatcher.dispatchPending(); }
}

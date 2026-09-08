package com.feng.medical.conversation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunReconciliationPort implements RunReconciliationPort {
    private final JdbcClient jdbc;
    JdbcRunReconciliationPort(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public int resetDispatchForUnfinishedRuns() {
        return jdbc.sql("UPDATE outbox_event event JOIN agent_run run ON event.aggregate_id = run.id "
                        + "SET event.dispatched_at = NULL, event.stream_id = NULL, event.last_error = 'INTERNAL_DELIVERY_RESTORED' "
                        + "WHERE event.event_type = 'RUN_QUEUED' AND run.status IN ('CREATED', 'RUNNING')")
                .update();
    }
}

package com.feng.medical.ingestion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOutboxEventRepository implements OutboxEventRepository {
    private final JdbcClient jdbc;
    JdbcOutboxEventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public List<OutboxEvent> findPendingIngestionEvents(int limit) {
        return jdbc.sql("SELECT id, aggregate_type, aggregate_id, event_type, payload, created_at FROM outbox_event "
                        + "WHERE dispatched_at IS NULL AND event_type = 'DOCUMENT_INGESTION_QUEUED' ORDER BY created_at LIMIT :limit")
                .param("limit", limit).query(this::map).list();
    }
    @Override public void markDispatched(UUID eventId, String streamId) {
        jdbc.sql("UPDATE outbox_event SET dispatched_at = CURRENT_TIMESTAMP(3), stream_id = :streamId, delivery_attempts = delivery_attempts + 1, last_error = NULL "
                        + "WHERE id = :id AND dispatched_at IS NULL").param("id", eventId.toString()).param("streamId", streamId).update();
    }
    @Override public void recordFailure(UUID eventId, String reason) {
        jdbc.sql("UPDATE outbox_event SET delivery_attempts = delivery_attempts + 1, last_error = :reason WHERE id = :id AND dispatched_at IS NULL")
                .param("id", eventId.toString()).param("reason", reason == null ? "unknown" : reason.substring(0, Math.min(500, reason.length()))).update();
    }
    private OutboxEvent map(ResultSet resultSet, int row) throws SQLException {
        return new OutboxEvent(UUID.fromString(resultSet.getString("id")), resultSet.getString("aggregate_type"), UUID.fromString(resultSet.getString("aggregate_id")),
                resultSet.getString("event_type"), resultSet.getString("payload"), resultSet.getTimestamp("created_at").toInstant());
    }
}

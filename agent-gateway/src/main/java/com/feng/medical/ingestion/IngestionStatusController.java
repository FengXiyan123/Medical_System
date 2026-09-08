package com.feng.medical.ingestion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator read model for every durable document-ingestion hand-off. */
@RestController
@RequestMapping("/api/admin/documents")
public class IngestionStatusController {
    private final JdbcClient jdbc;

    public IngestionStatusController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{documentId}/ingestion")
    public IngestionStatusView status(@PathVariable UUID documentId) {
        return jdbc.sql("""
                        SELECT job.id, job.status, job.stage, job.progress, job.error_code, job.updated_at,
                               event.dispatched_at, event.delivery_attempts, event.last_error
                        FROM ingest_job job
                        LEFT JOIN outbox_event event ON event.aggregate_id = job.document_id
                            AND event.event_type = 'DOCUMENT_INGESTION_QUEUED'
                        WHERE job.document_id = :documentId
                        ORDER BY job.created_at DESC LIMIT 1
                        """)
                .param("documentId", documentId.toString())
                .query(this::map).optional()
                .orElse(new IngestionStatusView(null, "NOT_QUEUED", null, 0, null, false, 0, null, null));
    }

    private IngestionStatusView map(ResultSet resultSet, int row) throws SQLException {
        var dispatchedAt = resultSet.getTimestamp("dispatched_at");
        var updatedAt = resultSet.getTimestamp("updated_at");
        return new IngestionStatusView(UUID.fromString(resultSet.getString("id")), resultSet.getString("status"),
                resultSet.getString("stage"), resultSet.getInt("progress"), resultSet.getString("error_code"),
                dispatchedAt != null, resultSet.getInt("delivery_attempts"), resultSet.getString("last_error"),
                updatedAt == null ? null : updatedAt.toInstant());
    }

    public record IngestionStatusView(UUID taskId, String status, String stage, int progress, String errorCode,
                                      boolean outboxDispatched, int deliveryAttempts, String deliveryError,
                                      Instant updatedAt) {
    }
}

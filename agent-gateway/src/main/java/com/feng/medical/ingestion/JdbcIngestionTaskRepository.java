package com.feng.medical.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIngestionTaskRepository implements IngestionTaskRepository {
    private final JdbcClient jdbc;
    JdbcIngestionTaskRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public IngestionTask create(IngestionTask task) {
        jdbc.sql("INSERT INTO ingest_job (id, document_id, stage, status, attempt_no, fencing_token, progress, created_at, updated_at) "
                        + "VALUES (:id, :documentId, :stage, :status, :attemptNo, :fencingToken, :progress, :createdAt, :createdAt)")
                .param("id", task.id().toString()).param("documentId", task.documentId().toString()).param("stage", task.stage().name())
                .param("status", task.status().name()).param("attemptNo", task.attemptNo()).param("fencingToken", task.fencingToken())
                .param("progress", task.progress()).param("createdAt", task.createdAt()).update();
        return task;
    }
}

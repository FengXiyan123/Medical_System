package com.feng.medical.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.feng.medical.file.UploadedDocument;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcIngestionEventOutbox implements IngestionEventOutbox {
    private final JdbcClient jdbc; private final ObjectMapper objectMapper;
    JdbcIngestionEventOutbox(JdbcClient jdbc, ObjectMapper objectMapper) { this.jdbc = jdbc; this.objectMapper = objectMapper; }
    @Override public void enqueue(IngestionTask task, UploadedDocument document) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(), "taskId", task.id().toString(),
                    "documentId", document.id().toString(), "knowledgeBaseId", document.knowledgeBaseId().toString(), "storageKey", document.storageKey(),
                    "mediaType", document.mediaType(), "contentSha256", document.contentSha256(), "stage", task.stage().name()));
            jdbc.sql("INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload) VALUES (:id, 'KNOWLEDGE_DOCUMENT', :documentId, 'DOCUMENT_INGESTION_QUEUED', :payload)")
                    .param("id", UUID.randomUUID().toString()).param("documentId", document.id().toString()).param("payload", payload).update();
        } catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化文档处理事件", exception); }
    }
}

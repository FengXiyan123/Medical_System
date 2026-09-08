package com.feng.medical.ingestion;

import com.feng.medical.knowledge.AgentCoreKnowledgeProperties;
import com.feng.medical.streaming.ServiceTokenIssuer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Explicit administrator fallback for a job that is durable but not consumed from Redis. */
@RestController
@RequestMapping("/api/admin/documents")
public class DirectIngestionController {
    private final JdbcClient jdbc;
    private final RestClient core;
    private final ServiceTokenIssuer tokens;

    public DirectIngestionController(JdbcClient jdbc, AgentCoreKnowledgeProperties properties, ServiceTokenIssuer tokens) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.core = RestClient.builder().baseUrl(properties.baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory()).build();
    }

    @PostMapping("/{documentId}/ingestion:execute")
    public DirectIngestionResult execute(@PathVariable UUID documentId) {
        DirectIngestionRequest request = jdbc.sql("""
                        SELECT event.id AS event_id, job.id AS task_id, document.id AS document_id,
                               document.knowledge_base_id, document.storage_key, document.content_sha256
                        FROM knowledge_document document
                        JOIN ingest_job job ON job.document_id = document.id
                        JOIN outbox_event event ON event.aggregate_id = document.id
                            AND event.event_type = 'DOCUMENT_INGESTION_QUEUED'
                        WHERE document.id = :documentId
                        ORDER BY job.created_at DESC LIMIT 1
                        """)
                .param("documentId", documentId.toString()).query(this::map).optional()
                .orElseThrow(() -> new IllegalArgumentException("文档没有可执行的处理任务"));
        try {
            DirectIngestionResult result = core.post().uri("/internal/ingestion/jobs")
                    .header("Authorization", "Bearer " + tokens.coreTicket("ingestion:execute"))
                    .body(request).retrieve().body(DirectIngestionResult.class);
            return result == null ? new DirectIngestionResult("ACCEPTED", documentId.toString()) : result;
        } catch (RestClientException error) {
            throw new IllegalStateException("Agent Core 无法执行文档处理：" + error.getMessage(), error);
        }
    }

    private DirectIngestionRequest map(ResultSet resultSet, int row) throws SQLException {
        return new DirectIngestionRequest(resultSet.getString("event_id"), resultSet.getString("task_id"),
                resultSet.getString("document_id"), resultSet.getString("knowledge_base_id"),
                resultSet.getString("storage_key"), resultSet.getString("content_sha256"));
    }

    public record DirectIngestionRequest(String eventId, String taskId, String documentId,
                                         String knowledgeBaseId, String storageKey, String contentSha256) {
    }
    public record DirectIngestionResult(String status, String documentId) {
    }
}

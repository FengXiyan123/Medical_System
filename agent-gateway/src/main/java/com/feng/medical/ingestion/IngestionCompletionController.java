package com.feng.medical.ingestion;

import com.feng.medical.streaming.ServiceCallbackTokenVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Receives an idempotent terminal result from Agent Core's Redis worker. */
@RestController
@RequestMapping("/api/internal/v1/ingestion")
public class IngestionCompletionController {
    private final ServiceCallbackTokenVerifier tokens;
    private final JdbcClient jdbc;

    public IngestionCompletionController(ServiceCallbackTokenVerifier tokens, JdbcClient jdbc) {
        this.tokens = tokens; this.jdbc = jdbc;
    }

    @PostMapping("/completions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public void complete(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                         @Valid @RequestBody Completion request) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new AccessDeniedException("缺少服务令牌");
        tokens.verifyIngestionCallback(authorization.substring(7));
        if (request.status().equals("RUNNING")) {
            jdbc.sql("UPDATE ingest_job SET status='RUNNING', stage=:stage, progress=:progress, error_code=NULL, "
                            + "lease_owner='agent-core', lease_until=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 2 MINUTE) "
                            + "WHERE id=:taskId AND status <> 'SUCCEEDED'")
                    .param("taskId", request.taskId().toString()).param("stage", request.stage())
                    .param("progress", request.progress()).update();
            jdbc.sql("UPDATE knowledge_document SET status='PROCESSING' WHERE id=:documentId AND status NOT IN ('READY','ARCHIVED')")
                    .param("documentId", request.documentId().toString()).update();
            return;
        }
        if (request.status().equals("FAILED")) {
            jdbc.sql("UPDATE ingest_job SET status='FAILED', progress=0, error_code=:error, lease_owner=NULL, lease_until=NULL WHERE id=:taskId AND status <> 'SUCCEEDED'")
                    .param("taskId", request.taskId().toString()).param("error", request.errorCode() == null ? "INGESTION_FAILED" : request.errorCode()).update();
            jdbc.sql("UPDATE knowledge_document SET status='FAILED' WHERE id=:documentId AND status <> 'READY'")
                    .param("documentId", request.documentId().toString()).update();
            return;
        }
        Integer existingVersion = jdbc.sql("SELECT generation_no FROM knowledge_generation WHERE id=:id")
                .param("id", request.generationId().toString()).query(Integer.class).optional().orElse(null);
        int nextVersion = existingVersion == null
                ? jdbc.sql("SELECT latest_version + 1 FROM knowledge_document WHERE id=:documentId")
                        .param("documentId", request.documentId().toString()).query(Integer.class).single()
                : existingVersion;
        jdbc.sql("""
                    INSERT INTO knowledge_generation
                    (id, document_id, generation_no, build_status, publication_status, edit_revision,
                     chunk_config_json, embedding_profile_id, embedding_dimension, chunk_count, content_manifest_hash)
                    VALUES (:id,:documentId,:generationNo,'BUILD_READY','DRAFT',0,JSON_OBJECT(),:profile,:dimension,:chunkCount,:manifest)
                    ON DUPLICATE KEY UPDATE build_status='BUILD_READY', embedding_profile_id=VALUES(embedding_profile_id),
                        embedding_dimension=VALUES(embedding_dimension), chunk_count=VALUES(chunk_count),
                        content_manifest_hash=VALUES(content_manifest_hash), indexed_at=CURRENT_TIMESTAMP(3)""")
                .param("id", request.generationId().toString()).param("documentId", request.documentId().toString()).param("generationNo", nextVersion)
                .param("profile", request.embeddingProfileId()).param("dimension", request.embeddingDimension()).param("chunkCount", request.chunkCount())
                .param("manifest", request.manifestHash()).update();
        jdbc.sql("UPDATE knowledge_document SET status='READY', latest_version=GREATEST(latest_version,:version) WHERE id=:documentId")
                .param("documentId", request.documentId().toString()).param("version", nextVersion).update();
        jdbc.sql("UPDATE ingest_job SET status='SUCCEEDED', stage='EMBED', progress=100, error_code=NULL, lease_owner=NULL, lease_until=NULL WHERE id=:taskId")
                .param("taskId", request.taskId().toString()).update();
    }

    public record Completion(@NotNull UUID eventId, @NotNull UUID taskId, @NotNull UUID documentId,
                             @NotBlank String status, String stage, @Min(0) @Max(100) Integer progress,
                             UUID generationId, String manifestHash,
                             @Min(1) Integer chunkCount, String embeddingProfileId,
                             @Min(1) @Max(1024) Integer embeddingDimension, String errorCode) {
        public Completion {
            if (!(status.equals("RUNNING") || status.equals("SUCCEEDED") || status.equals("FAILED"))) throw new IllegalArgumentException("无效处理状态");
            if (status.equals("RUNNING") && (stage == null || progress == null)) throw new IllegalArgumentException("处理中回调缺少阶段或进度");
            if (status.equals("SUCCEEDED") && (generationId == null || manifestHash == null || embeddingProfileId == null || chunkCount == null || embeddingDimension == null)) {
                throw new IllegalArgumentException("成功回调缺少生成版本信息");
            }
        }
    }
}

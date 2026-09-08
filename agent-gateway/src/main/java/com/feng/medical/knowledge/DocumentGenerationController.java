package com.feng.medical.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read model for the administrator's document and chunk workflow. */
@RestController
@RequestMapping("/api/admin/documents")
public class DocumentGenerationController {
    private final JdbcClient jdbc;
    public DocumentGenerationController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/{documentId}/generations")
    public List<GenerationView> list(@PathVariable UUID documentId) {
        return jdbc.sql("SELECT id,document_id,generation_no,build_status,publication_status,edit_revision,chunk_count,content_manifest_hash,indexed_at "
                        + "FROM knowledge_generation WHERE document_id=:documentId ORDER BY generation_no DESC")
                .param("documentId", documentId.toString()).query((rs, row) -> new GenerationView(
                        UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("document_id")), rs.getInt("generation_no"),
                        GenerationBuildStatus.valueOf(rs.getString("build_status")), PublicationStatus.valueOf(rs.getString("publication_status")),
                        rs.getLong("edit_revision"), rs.getInt("chunk_count"), rs.getString("content_manifest_hash"),
                        rs.getTimestamp("indexed_at") == null ? null : rs.getTimestamp("indexed_at").toInstant())).list();
    }
    public record GenerationView(UUID id, UUID documentId, int generationNo, GenerationBuildStatus buildStatus,
                                 PublicationStatus publicationStatus, long editRevision, int chunkCount,
                                 String manifestHash, Instant indexedAt) { }
}

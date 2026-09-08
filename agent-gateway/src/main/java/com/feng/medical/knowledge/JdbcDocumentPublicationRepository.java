package com.feng.medical.knowledge;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentPublicationRepository implements DocumentPublicationRepository {
    private final JdbcTemplate jdbc;

    public JdbcDocumentPublicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID activeGenerationId(UUID documentId) {
        return jdbc.query("SELECT active_generation_id FROM knowledge_document WHERE id = ?", rs -> {
            if (!rs.next() || rs.getString(1) == null) {
                return null;
            }
            return UUID.fromString(rs.getString(1));
        }, documentId.toString());
    }

    @Override
    public boolean switchActiveGeneration(UUID documentId, UUID expectedActiveGenerationId, UUID nextGenerationId) {
        int changed;
        if (expectedActiveGenerationId == null) {
            changed = jdbc.update("UPDATE knowledge_document SET active_generation_id = ? "
                            + "WHERE id = ? AND active_generation_id IS NULL",
                    nextGenerationId.toString(), documentId.toString());
        } else {
            changed = jdbc.update("UPDATE knowledge_document SET active_generation_id = ? "
                            + "WHERE id = ? AND active_generation_id = ?",
                    nextGenerationId.toString(), documentId.toString(), expectedActiveGenerationId.toString());
        }
        return changed == 1;
    }

    @Override
    public void setPublicationStatus(UUID generationId, PublicationStatus status) {
        if (jdbc.update("UPDATE knowledge_generation SET publication_status = ? WHERE id = ?",
                status.name(), generationId.toString()) != 1) {
            throw new IllegalArgumentException("knowledge generation does not exist");
        }
    }
}

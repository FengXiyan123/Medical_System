package com.feng.medical.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcKnowledgeScopeRepository implements KnowledgeScopeRepository {
    private final JdbcClient jdbc;

    JdbcKnowledgeScopeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public List<UUID> findAccessibleKnowledgeBaseIds(UUID userId) {
        return jdbc.sql("SELECT DISTINCT kb.id FROM knowledge_base kb "
                        + "LEFT JOIN knowledge_base_user_grant grant_row ON grant_row.knowledge_base_id = kb.id AND grant_row.user_id = :userId "
                        + "WHERE kb.status = 'PUBLISHED' AND (kb.access_scope = 'ALL_AUTHENTICATED' OR grant_row.user_id IS NOT NULL) ORDER BY kb.id")
                .param("userId", userId.toString()).query(String.class).list().stream().map(UUID::fromString).toList();
    }

    @Override public List<ActiveKnowledgeGeneration> findActiveAccessibleGenerations(UUID userId) {
        return jdbc.sql("SELECT kb.id AS knowledge_base_id, document_row.id AS document_id, generation_row.id AS generation_id, kb.authz_version "
                        + "FROM knowledge_base kb "
                        + "JOIN knowledge_document document_row ON document_row.knowledge_base_id = kb.id "
                        + "JOIN knowledge_generation generation_row ON generation_row.id = document_row.active_generation_id "
                        + "LEFT JOIN knowledge_base_user_grant grant_row ON grant_row.knowledge_base_id = kb.id AND grant_row.user_id = :userId "
                        + "WHERE kb.status = 'PUBLISHED' AND document_row.status = 'READY' "
                        + "AND generation_row.build_status = 'BUILD_READY' AND generation_row.publication_status = 'ACTIVE' "
                        + "AND (kb.access_scope = 'ALL_AUTHENTICATED' OR grant_row.user_id IS NOT NULL) "
                        + "ORDER BY kb.id, document_row.id")
                .param("userId", userId.toString()).query((rs, row) -> new ActiveKnowledgeGeneration(
                        UUID.fromString(rs.getString("knowledge_base_id")), UUID.fromString(rs.getString("document_id")),
                        UUID.fromString(rs.getString("generation_id")), rs.getLong("authz_version"))).list();
    }
}

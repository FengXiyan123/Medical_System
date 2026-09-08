package com.feng.medical.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcKnowledgeBaseRepository implements KnowledgeBaseRepository {
    private final JdbcClient jdbc;

    JdbcKnowledgeBaseRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public KnowledgeBase create(KnowledgeBase value) {
        jdbc.sql("INSERT INTO knowledge_base (id, name, description, status, access_scope, created_by, created_at, updated_at) "
                        + "VALUES (:id, :name, :description, :status, :scope, :createdBy, :createdAt, :updatedAt)")
                .param("id", value.id().toString()).param("name", value.name()).param("description", value.description())
                .param("status", value.status().name()).param("scope", value.accessScope().name())
                .param("createdBy", value.createdBy().toString()).param("createdAt", value.createdAt()).param("updatedAt", value.updatedAt()).update();
        return value;
    }

    @Override public KnowledgeBase findById(UUID id) {
        return jdbc.sql("SELECT id, name, description, status, access_scope, created_by, created_at, updated_at FROM knowledge_base WHERE id = :id")
                .param("id", id.toString()).query(this::map).optional().orElse(null);
    }

    @Override public List<KnowledgeBase> listAll() {
        return jdbc.sql("SELECT id, name, description, status, access_scope, created_by, created_at, updated_at FROM knowledge_base ORDER BY name")
                .query(this::map).list();
    }

    @Override public KnowledgeBase update(KnowledgeBase value) {
        jdbc.sql("UPDATE knowledge_base SET name = :name, description = :description, status = :status, access_scope = :scope, authz_version = authz_version + 1, updated_at = :updatedAt WHERE id = :id")
                .param("id", value.id().toString()).param("name", value.name()).param("description", value.description())
                .param("status", value.status().name()).param("scope", value.accessScope().name()).param("updatedAt", value.updatedAt()).update();
        return value;
    }

    @Override public void replaceGrants(UUID knowledgeBaseId, Collection<UUID> userIds, UUID grantedBy) {
        jdbc.sql("DELETE FROM knowledge_base_user_grant WHERE knowledge_base_id = :knowledgeBaseId")
                .param("knowledgeBaseId", knowledgeBaseId.toString()).update();
        userIds.forEach(userId -> jdbc.sql("INSERT INTO knowledge_base_user_grant (knowledge_base_id, user_id, granted_by) VALUES (:knowledgeBaseId, :userId, :grantedBy)")
                .param("knowledgeBaseId", knowledgeBaseId.toString()).param("userId", userId.toString()).param("grantedBy", grantedBy.toString()).update());
        jdbc.sql("UPDATE knowledge_base SET authz_version = authz_version + 1 WHERE id = :knowledgeBaseId")
                .param("knowledgeBaseId", knowledgeBaseId.toString()).update();
    }

    @Override public List<KnowledgeBase> listAccessible(UUID userId) {
        return jdbc.sql("SELECT kb.id, kb.name, kb.description, kb.status, kb.access_scope, kb.created_by, kb.created_at, kb.updated_at "
                        + "FROM knowledge_base kb LEFT JOIN knowledge_base_user_grant grant_row "
                        + "ON grant_row.knowledge_base_id = kb.id AND grant_row.user_id = :userId "
                        + "WHERE kb.status = 'PUBLISHED' AND (kb.access_scope = 'ALL_AUTHENTICATED' OR grant_row.user_id IS NOT NULL) ORDER BY kb.name")
                .param("userId", userId.toString()).query(this::map).list();
    }

    @Override public boolean canAccess(UUID userId, UUID knowledgeBaseId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM knowledge_base kb LEFT JOIN knowledge_base_user_grant grant_row "
                        + "ON grant_row.knowledge_base_id = kb.id AND grant_row.user_id = :userId "
                        + "WHERE kb.id = :knowledgeBaseId AND kb.status = 'PUBLISHED' "
                        + "AND (kb.access_scope = 'ALL_AUTHENTICATED' OR grant_row.user_id IS NOT NULL)")
                .param("userId", userId.toString()).param("knowledgeBaseId", knowledgeBaseId.toString()).query(Integer.class).single();
        return count != null && count > 0;
    }

    private KnowledgeBase map(ResultSet resultSet, int rowNum) throws SQLException {
        return new KnowledgeBase(UUID.fromString(resultSet.getString("id")), resultSet.getString("name"), resultSet.getString("description"),
                KnowledgeBaseStatus.valueOf(resultSet.getString("status")), KnowledgeAccessScope.valueOf(resultSet.getString("access_scope")),
                UUID.fromString(resultSet.getString("created_by")), resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }
}

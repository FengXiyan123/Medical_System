package com.feng.medical.file;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {
    private final JdbcClient jdbc;
    JdbcKnowledgeDocumentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public UploadedDocument create(UploadedDocument value) {
        jdbc.sql("INSERT INTO knowledge_document (id, knowledge_base_id, original_filename, media_type, storage_key, content_sha256, status, latest_version, uploaded_by, created_at, updated_at) "
                        + "VALUES (:id, :knowledgeBaseId, :originalFilename, :mediaType, :storageKey, :contentSha256, :status, :latestVersion, :uploadedBy, :createdAt, :updatedAt)")
                .param("id", value.id().toString()).param("knowledgeBaseId", value.knowledgeBaseId().toString())
                .param("originalFilename", value.originalFilename()).param("mediaType", value.mediaType()).param("storageKey", value.storageKey())
                .param("contentSha256", value.contentSha256()).param("status", value.status().name()).param("latestVersion", value.latestVersion())
                .param("uploadedBy", value.uploadedBy().toString()).param("createdAt", value.createdAt()).param("updatedAt", value.updatedAt()).update();
        return value;
    }
    @Override public UploadedDocument findByKnowledgeBaseAndHash(UUID knowledgeBaseId, String hash) {
        return jdbc.sql("SELECT id, knowledge_base_id, original_filename, media_type, storage_key, content_sha256, status, latest_version, uploaded_by, created_at, updated_at "
                        + "FROM knowledge_document WHERE knowledge_base_id = :knowledgeBaseId AND content_sha256 = :hash")
                .param("knowledgeBaseId", knowledgeBaseId.toString()).param("hash", hash).query(this::map).optional().orElse(null);
    }
    @Override public List<UploadedDocument> listByKnowledgeBase(UUID knowledgeBaseId) {
        return jdbc.sql("SELECT id, knowledge_base_id, original_filename, media_type, storage_key, content_sha256, status, latest_version, uploaded_by, created_at, updated_at "
                        + "FROM knowledge_document WHERE knowledge_base_id=:knowledgeBaseId ORDER BY updated_at DESC")
                .param("knowledgeBaseId", knowledgeBaseId.toString()).query(this::map).list();
    }
    private UploadedDocument map(ResultSet resultSet, int row) throws SQLException {
        return new UploadedDocument(UUID.fromString(resultSet.getString("id")), UUID.fromString(resultSet.getString("knowledge_base_id")),
                resultSet.getString("original_filename"), resultSet.getString("media_type"), resultSet.getString("storage_key"),
                resultSet.getString("content_sha256"), DocumentStatus.valueOf(resultSet.getString("status")), resultSet.getInt("latest_version"),
                UUID.fromString(resultSet.getString("uploaded_by")), resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }
}

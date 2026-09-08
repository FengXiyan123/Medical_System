package com.feng.medical.conversation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcConversationRepository implements ConversationRepository {
    private final JdbcClient jdbc;

    JdbcConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Conversation create(UUID userId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversation (id, user_id, title, status) VALUES (:id, :userId, :title, 'ACTIVE')")
                .param("id", id.toString()).param("userId", userId.toString()).param("title", title).update();
        return findOwned(userId, id);
    }

    @Override
    public Conversation findOwned(UUID userId, UUID id) {
        return jdbc.sql("SELECT id, user_id, title, status, created_at, updated_at FROM conversation "
                        + "WHERE id = :id AND user_id = :userId")
                .param("id", id.toString()).param("userId", userId.toString()).query(this::map).optional().orElse(null);
    }

    @Override
    public List<Conversation> listOwned(UUID userId, ConversationCursor cursor, int limit) {
        String sql = "SELECT id, user_id, title, status, created_at, updated_at FROM conversation "
                + "WHERE user_id = :userId AND status = 'ACTIVE' "
                + (cursor == null ? "" : "AND (updated_at < :updatedAt OR (updated_at = :updatedAt AND id < :id)) ")
                + "ORDER BY updated_at DESC, id DESC LIMIT :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("userId", userId.toString()).param("limit", limit);
        if (cursor != null) {
            spec.param("updatedAt", Timestamp.from(cursor.createdAt())).param("id", cursor.id().toString());
        }
        return spec.query(this::map).list();
    }

    @Override
    public Conversation updateOwned(UUID userId, UUID id, String title, ConversationStatus status) {
        if (title != null && status != null) {
            jdbc.sql("UPDATE conversation SET title = :title, status = :status WHERE id = :id AND user_id = :userId")
                    .param("title", title).param("status", status.name()).param("id", id.toString()).param("userId", userId.toString()).update();
        } else if (title != null) {
            jdbc.sql("UPDATE conversation SET title = :title WHERE id = :id AND user_id = :userId")
                    .param("title", title).param("id", id.toString()).param("userId", userId.toString()).update();
        } else if (status != null) {
            jdbc.sql("UPDATE conversation SET status = :status WHERE id = :id AND user_id = :userId")
                    .param("status", status.name()).param("id", id.toString()).param("userId", userId.toString()).update();
        }
        return findOwned(userId, id);
    }

    @Override
    public boolean deleteOwned(UUID userId, UUID id) {
        String conversationId = id.toString();
        jdbc.sql("DELETE feedback FROM feedback JOIN agent_run run ON feedback.run_id = run.id "
                        + "WHERE run.conversation_id = :conversationId")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM outbox_event WHERE aggregate_type = 'AGENT_RUN' "
                        + "AND aggregate_id IN (SELECT id FROM agent_run WHERE conversation_id = :conversationId)")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM usage_projection WHERE run_id IN (SELECT id FROM agent_run WHERE conversation_id = :conversationId)")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM model_invocation_trace WHERE run_id IN (SELECT id FROM agent_run WHERE conversation_id = :conversationId)")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM run_trace_span WHERE run_id IN (SELECT id FROM agent_run WHERE conversation_id = :conversationId)")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM run_knowledge_trace WHERE run_id IN (SELECT id FROM agent_run WHERE conversation_id = :conversationId)")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM agent_run_event WHERE run_id IN (SELECT id FROM agent_run WHERE conversation_id = :conversationId)")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM agent_run_knowledge_base WHERE run_id IN (SELECT id FROM agent_run WHERE conversation_id = :conversationId)")
                .param("conversationId", conversationId).update();
        jdbc.sql("UPDATE agent_run child JOIN agent_run parent ON child.regeneration_of_run_id = parent.id "
                        + "SET child.regeneration_of_run_id = NULL WHERE parent.conversation_id = :conversationId")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM agent_run WHERE conversation_id = :conversationId")
                .param("conversationId", conversationId).update();
        jdbc.sql("DELETE FROM chat_message WHERE conversation_id = :conversationId")
                .param("conversationId", conversationId).update();
        return jdbc.sql("DELETE FROM conversation WHERE id = :conversationId AND user_id = :userId")
                .param("conversationId", conversationId).param("userId", userId.toString()).update() == 1;
    }

    private Conversation map(java.sql.ResultSet resultSet, int rowNum) throws java.sql.SQLException {
        return new Conversation(UUID.fromString(resultSet.getString("id")), UUID.fromString(resultSet.getString("user_id")),
                resultSet.getString("title"), ConversationStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant());
    }
}

package com.feng.medical.conversation;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcChatMessageRepository implements ChatMessageRepository {
    private final JdbcClient jdbc;

    JdbcChatMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(ChatMessage message) {
        jdbc.sql("INSERT INTO chat_message (id, conversation_id, run_id, role, content, citation_manifest, created_at) "
                        + "VALUES (:id, :conversationId, :runId, :role, :content, :citationManifest, :createdAt)")
                .param("id", message.id().toString()).param("conversationId", message.conversationId().toString())
                .param("runId", message.runId() == null ? null : message.runId().toString()).param("role", message.role().name())
                .param("content", message.content()).param("citationManifest", message.citationManifest())
                .param("createdAt", Timestamp.from(message.createdAt())).update();
    }

    @Override
    public List<ChatMessage> list(UUID conversationId, MessageCursor cursor, int limit) {
        String sql = "SELECT id, conversation_id, run_id, role, content, citation_manifest, created_at FROM chat_message "
                + "WHERE conversation_id = :conversationId "
                + (cursor == null ? "" : "AND (created_at > :createdAt OR (created_at = :createdAt AND id > :id)) ")
                + "ORDER BY created_at ASC, id ASC LIMIT :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("conversationId", conversationId.toString()).param("limit", limit);
        if (cursor != null) {
            spec.param("createdAt", Timestamp.from(cursor.createdAt())).param("id", cursor.id().toString());
        }
        return spec.query((rs, row) -> new ChatMessage(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("conversation_id")), rs.getString("run_id") == null ? null : UUID.fromString(rs.getString("run_id")),
                MessageRole.valueOf(rs.getString("role")), rs.getString("content"), rs.getTimestamp("created_at").toInstant(),
                rs.getString("citation_manifest"))).list();
    }
}

package com.feng.medical.conversation;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunRepository implements RunRepository {
    private final JdbcClient jdbc;

    JdbcRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AgentRun findByUserAndIdempotencyKey(UUID userId, String key) {
        return jdbc.sql(select() + " WHERE user_id = :userId AND idempotency_key = :key")
                .param("userId", userId.toString()).param("key", key).query(this::map).optional().orElse(null);
    }

    @Override
    public AgentRun findOwned(UUID userId, UUID conversationId, UUID runId) {
        return jdbc.sql(select() + " WHERE id = :id AND user_id = :userId AND conversation_id = :conversationId")
                .param("id", runId.toString()).param("userId", userId.toString()).param("conversationId", conversationId.toString())
                .query(this::map).optional().orElse(null);
    }

    @Override
    public List<String> selectedKnowledgeBaseIds(UUID runId) {
        return jdbc.sql("SELECT knowledge_base_id FROM agent_run_knowledge_base WHERE run_id = :runId ORDER BY knowledge_base_id")
                .param("runId", runId.toString()).query(String.class).list();
    }

    @Override
    public boolean hasActiveRun(UUID conversationId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM agent_run WHERE conversation_id = :conversationId AND status IN ('CREATED', 'RUNNING')")
                .param("conversationId", conversationId.toString()).query(Integer.class).single();
        return count != null && count > 0;
    }

    @Override
    public AgentRun create(AgentRun run) {
        jdbc.sql("INSERT INTO agent_run (id, trace_id, user_id, conversation_id, mode, status, question, idempotency_key, request_hash, request_message_id, regeneration_of_run_id, created_at) "
                        + "VALUES (:id, :traceId, :userId, :conversationId, :mode, :status, :question, :idempotencyKey, :requestHash, :requestMessageId, :regenerationOfRunId, :createdAt)")
                .param("id", run.id().toString()).param("traceId", run.traceId().toString()).param("userId", run.userId().toString())
                .param("conversationId", run.conversationId().toString()).param("mode", run.mode().name()).param("status", run.status().name())
                .param("question", run.question()).param("idempotencyKey", run.idempotencyKey()).param("requestHash", run.requestHash())
                .param("requestMessageId", run.requestMessageId() == null ? null : run.requestMessageId().toString())
                .param("regenerationOfRunId", run.regenerationOfRunId() == null ? null : run.regenerationOfRunId().toString())
                .param("createdAt", Timestamp.from(run.createdAt())).update();
        return run;
    }

    private String select() {
        return "SELECT id, trace_id, user_id, conversation_id, mode, status, question, idempotency_key, request_hash, request_message_id, regeneration_of_run_id, created_at FROM agent_run";
    }

    private AgentRun map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String messageId = rs.getString("request_message_id");
        String regenerationId = rs.getString("regeneration_of_run_id");
        return new AgentRun(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("trace_id")),
                UUID.fromString(rs.getString("user_id")), UUID.fromString(rs.getString("conversation_id")), RunMode.valueOf(rs.getString("mode")),
                RunStatus.valueOf(rs.getString("status")), rs.getString("question"), rs.getString("idempotency_key"),
                rs.getString("request_hash"), messageId == null ? null : UUID.fromString(messageId),
                regenerationId == null ? null : UUID.fromString(regenerationId), rs.getTimestamp("created_at").toInstant());
    }
}

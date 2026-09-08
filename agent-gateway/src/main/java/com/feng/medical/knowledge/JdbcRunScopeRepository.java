package com.feng.medical.knowledge;

import com.feng.medical.conversation.AgentRun;
import com.feng.medical.conversation.RunMode;
import com.feng.medical.conversation.RunStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunScopeRepository implements RunScopeRepository {
    private final JdbcClient jdbc;

    JdbcRunScopeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public AgentRun findById(UUID runId) {
        return jdbc.sql("SELECT id, trace_id, user_id, conversation_id, mode, status, question, idempotency_key, request_hash, request_message_id, regeneration_of_run_id, created_at FROM agent_run WHERE id = :id")
                .param("id", runId.toString()).query((rs, row) -> {
                    String messageId = rs.getString("request_message_id");
                    String regenerationId = rs.getString("regeneration_of_run_id");
                    return new AgentRun(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("trace_id")),
                            UUID.fromString(rs.getString("user_id")), UUID.fromString(rs.getString("conversation_id")),
                            RunMode.valueOf(rs.getString("mode")), RunStatus.valueOf(rs.getString("status")),
                            rs.getString("question"), rs.getString("idempotency_key"), rs.getString("request_hash"),
                            messageId == null ? null : UUID.fromString(messageId),
                            regenerationId == null ? null : UUID.fromString(regenerationId), rs.getTimestamp("created_at").toInstant());
                }).optional().orElse(null);
    }

    @Override public List<String> selectedKnowledgeBaseIds(UUID runId) {
        return jdbc.sql("SELECT knowledge_base_id FROM agent_run_knowledge_base WHERE run_id = :runId ORDER BY knowledge_base_id")
                .param("runId", runId.toString()).query(String.class).list();
    }
}

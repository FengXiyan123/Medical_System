package com.feng.medical.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunDispatchPort implements RunDispatchPort {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    JdbcRunDispatchPort(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void enqueue(AgentRun run, List<String> selectedKnowledgeBaseIds) {
        selectedKnowledgeBaseIds.forEach(knowledgeBaseId -> jdbc.sql(
                        "INSERT INTO agent_run_knowledge_base (run_id, knowledge_base_id) VALUES (:runId, :knowledgeBaseId)")
                .param("runId", run.id().toString()).param("knowledgeBaseId", knowledgeBaseId).update());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "eventId", UUID.randomUUID().toString(),
                    "runId", run.id().toString(),
                    "traceId", run.traceId().toString(),
                    "userId", run.userId().toString(),
                    "conversationId", run.conversationId().toString(),
                    "mode", run.mode().name(),
                    "question", run.question(),
                    "selectedKnowledgeBaseIds", selectedKnowledgeBaseIds));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化运行投递事件", exception);
        }
        jdbc.sql("INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload) "
                        + "VALUES (:id, 'AGENT_RUN', :runId, 'RUN_QUEUED', :payload)")
                .param("id", UUID.randomUUID().toString()).param("runId", run.id().toString()).param("payload", payload).update();
    }
}

package com.feng.medical.streaming;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Makes terminal callbacks durable in the user-facing message history exactly once. */
@Repository
class JdbcRunEventFinalizer implements RunEventFinalizer {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    JdbcRunEventFinalizer(JdbcClient jdbc, ObjectMapper objectMapper) { this.jdbc = jdbc; this.objectMapper = objectMapper; }

    @Override public void finalizeRun(RunEvent event) {
        if (event.type() == RunEventType.RUN_STARTED) {
            jdbc.sql("UPDATE agent_run SET status = 'RUNNING' WHERE id = :runId AND status = 'CREATED'")
                    .param("runId", event.runId().toString()).update();
            return;
        }
        if (event.type() == RunEventType.RETRIEVAL_COMPLETED) {
            persistRetrievalEvidence(event);
            return;
        }
        String status = switch (event.type()) {
            case RUN_COMPLETED -> "SUCCEEDED";
            case RUN_FAILED -> "FAILED";
            case RUN_CANCELLED -> "CANCELLED";
            default -> throw new IllegalArgumentException("只有终态事件可以完成运行");
        };
        if (event.type() == RunEventType.RUN_COMPLETED) createAnswerIfAbsent(event);
        jdbc.sql("UPDATE agent_run SET status = :status, completed_at = CURRENT_TIMESTAMP(3) "
                        + "WHERE id = :runId AND status IN ('CREATED', 'RUNNING')")
                .param("status", status).param("runId", event.runId().toString()).update();
    }

    @SuppressWarnings("unchecked")
    private void persistRetrievalEvidence(RunEvent event) {
        try {
            java.util.Map<String, Object> payload = objectMapper.readValue(event.payload(), new TypeReference<>() { });
            Object raw = payload.get("evidence");
            if (!(raw instanceof java.util.List<?> evidence)) return;
            for (Object item : evidence) {
                if (!(item instanceof java.util.Map<?, ?> value)) continue;
                Object chunk = value.get("chunkId"); Object stage = value.get("stage");
                if (!(chunk instanceof String chunkId) || !(stage instanceof String sourceStage)) continue;
                String persistedStage = switch (sourceStage) { case "RECALL" -> "RETRIEVED"; case "RERANK" -> "RERANKED"; case "CONTEXT" -> "CONTEXT_INCLUDED"; default -> null; };
                if (persistedStage == null) continue;
                Object text = value.get("text"); Object rank = value.get("rank");
                String snapshot = objectMapper.writeValueAsString(java.util.Map.of("text", text == null ? "" : text, "rank", rank == null ? 0 : rank));
                Object score = value.get("score");
                jdbc.sql("""
                            INSERT INTO run_knowledge_trace
                            (id,run_id,chunk_id,stage,score,selection_reason,knowledge_base_id,generation_id,chunk_snapshot)
                            VALUES (:id,:runId,:chunkId,:stage,:score,:reason,:knowledgeBaseId,:generationId,CAST(:snapshot AS JSON))
                            ON DUPLICATE KEY UPDATE score=VALUES(score), selection_reason=VALUES(selection_reason), chunk_snapshot=VALUES(chunk_snapshot)""")
                        .param("id", UUID.randomUUID().toString()).param("runId", event.runId().toString()).param("chunkId", chunkId)
                        .param("stage", persistedStage).param("score", score instanceof Number number ? number.doubleValue() : null)
                        .param("reason", "agent-core retrieval " + sourceStage).param("knowledgeBaseId", value.get("knowledgeBaseId"))
                        .param("generationId", value.get("generationId")).param("snapshot", snapshot).update();
            }
        } catch (Exception error) { throw new IllegalArgumentException("检索证据不是有效 JSON", error); }
    }

    private void createAnswerIfAbsent(RunEvent event) {
        java.util.Map<String, Object> payload;
        try { payload = objectMapper.readValue(event.payload(), new TypeReference<>() { }); }
        catch (Exception error) { throw new IllegalArgumentException("完成事件不是有效 JSON", error); }
        String answer = payload.get("answer") instanceof String value ? value : "";
        String citations;
        try { citations = payload.containsKey("citations") ? objectMapper.writeValueAsString(payload.get("citations")) : null; }
        catch (Exception error) { throw new IllegalArgumentException("完成事件 citations 不是有效 JSON", error); }
        if (answer.isBlank()) return;
        UUID messageId = UUID.randomUUID();
        jdbc.sql("INSERT INTO chat_message (id, conversation_id, run_id, role, content, citation_manifest, created_at) "
                        + "SELECT :messageId, conversation_id, id, 'ASSISTANT', :content, CAST(:citations AS JSON), :createdAt FROM agent_run "
                        + "WHERE id = :runId AND NOT EXISTS (SELECT 1 FROM chat_message WHERE run_id = :runId AND role = 'ASSISTANT')")
                .param("messageId", messageId.toString()).param("runId", event.runId().toString()).param("content", answer)
                .param("citations", citations).param("createdAt", java.sql.Timestamp.from(Instant.now())).update();
        jdbc.sql("UPDATE agent_run SET answer_message_id = COALESCE(answer_message_id, :messageId) WHERE id = :runId")
                .param("messageId", messageId.toString()).param("runId", event.runId().toString()).update();
    }
}

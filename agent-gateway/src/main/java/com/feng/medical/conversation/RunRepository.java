package com.feng.medical.conversation;

import java.util.UUID;
import java.util.List;

public interface RunRepository {
    AgentRun findByUserAndIdempotencyKey(UUID userId, String key);
    AgentRun findOwned(UUID userId, UUID conversationId, UUID runId);
    List<String> selectedKnowledgeBaseIds(UUID runId);
    boolean hasActiveRun(UUID conversationId);
    AgentRun create(AgentRun run);
}

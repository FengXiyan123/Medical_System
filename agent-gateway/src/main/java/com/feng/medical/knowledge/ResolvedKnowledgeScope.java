package com.feng.medical.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.feng.medical.conversation.RunMode;
import java.util.List;
import java.util.UUID;

/** Immutable scope passed to Agent Core and used as a mandatory query predicate. */
public record ResolvedKnowledgeScope(@JsonProperty("run_id") UUID runId,
                                     @JsonProperty("user_id") UUID userId, RunMode mode,
                                     @JsonProperty("knowledge_base_ids") List<UUID> knowledgeBaseIds,
                                     List<ActiveKnowledgeGeneration> generations) {
    public ResolvedKnowledgeScope {
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
        generations = List.copyOf(generations);
    }
}

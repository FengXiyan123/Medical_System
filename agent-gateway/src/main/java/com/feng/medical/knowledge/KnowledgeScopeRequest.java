package com.feng.medical.knowledge;

import com.feng.medical.conversation.RunMode;
import java.util.List;
import java.util.UUID;

/** Python must supply the bound run identity; it cannot nominate an arbitrary user scope. */
public record KnowledgeScopeRequest(UUID runId, UUID userId, RunMode mode,
                                    List<UUID> requestedKnowledgeBaseIds) {
}

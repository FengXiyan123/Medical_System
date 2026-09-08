package com.feng.medical.knowledge;

import java.util.List;
import java.util.UUID;

interface KnowledgeScopeRepository {
    /** Query includes only PUBLISHED knowledge bases and active BUILD_READY generations. */
    List<ActiveKnowledgeGeneration> findActiveAccessibleGenerations(UUID userId);

    /**
     * Kept separate from active generations so an authorized but empty library
     * yields an empty retrieval result instead of a false authorization denial.
     */
    default List<UUID> findAccessibleKnowledgeBaseIds(UUID userId) {
        return findActiveAccessibleGenerations(userId).stream()
                .map(ActiveKnowledgeGeneration::knowledgeBaseId).distinct().toList();
    }
}

package com.feng.medical.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseRepository {
    KnowledgeBase create(KnowledgeBase value);
    KnowledgeBase findById(UUID id);
    List<KnowledgeBase> listAll();
    KnowledgeBase update(KnowledgeBase value);
    void replaceGrants(UUID knowledgeBaseId, Collection<UUID> userIds, UUID grantedBy);
    List<KnowledgeBase> listAccessible(UUID userId);
    boolean canAccess(UUID userId, UUID knowledgeBaseId);
}

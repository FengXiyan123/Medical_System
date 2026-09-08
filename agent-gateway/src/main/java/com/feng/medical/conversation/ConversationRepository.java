package com.feng.medical.conversation;

import java.util.List;
import java.util.UUID;

public interface ConversationRepository {
    Conversation create(UUID userId, String title);
    Conversation findOwned(UUID userId, UUID id);
    List<Conversation> listOwned(UUID userId, ConversationCursor cursor, int limit);
    Conversation updateOwned(UUID userId, UUID id, String title, ConversationStatus status);
    boolean deleteOwned(UUID userId, UUID id);
}

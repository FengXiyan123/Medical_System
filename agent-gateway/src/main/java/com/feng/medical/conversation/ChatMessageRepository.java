package com.feng.medical.conversation;

import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository {
    void create(ChatMessage message);
    List<ChatMessage> list(UUID conversationId, MessageCursor cursor, int limit);
}

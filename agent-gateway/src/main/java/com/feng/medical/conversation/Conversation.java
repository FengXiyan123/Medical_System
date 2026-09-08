package com.feng.medical.conversation;

import java.time.Instant;
import java.util.UUID;

public record Conversation(UUID id, UUID userId, String title, ConversationStatus status,
                           Instant createdAt, Instant updatedAt) {
}

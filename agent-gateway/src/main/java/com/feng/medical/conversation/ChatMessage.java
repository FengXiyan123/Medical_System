package com.feng.medical.conversation;

import java.time.Instant;
import java.util.UUID;

public record ChatMessage(UUID id, UUID conversationId, UUID runId, MessageRole role,
                          String content, Instant createdAt, String citationManifest) {
}

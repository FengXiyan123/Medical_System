package com.feng.medical.conversation;

import java.time.Instant;
import java.util.UUID;

record ConversationCursor(Instant createdAt, UUID id) {
}

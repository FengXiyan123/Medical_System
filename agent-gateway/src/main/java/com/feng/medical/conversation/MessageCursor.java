package com.feng.medical.conversation;

import java.time.Instant;
import java.util.UUID;

record MessageCursor(Instant createdAt, UUID id) {
}

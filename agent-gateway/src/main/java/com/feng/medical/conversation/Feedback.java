package com.feng.medical.conversation;

import java.time.Instant;
import java.util.UUID;

public record Feedback(UUID answerId, UUID runId, UUID userId, FeedbackRating rating,
                       String reason, String comment, Instant updatedAt) {
}

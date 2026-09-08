package com.feng.medical.conversation;

import java.util.List;
import java.util.UUID;

interface FeedbackRepository {
    AnswerTarget findOwnedAnswer(UUID answerId, UUID userId);
    Feedback upsert(Feedback feedback);
    List<Feedback> findByRun(UUID runId);
}

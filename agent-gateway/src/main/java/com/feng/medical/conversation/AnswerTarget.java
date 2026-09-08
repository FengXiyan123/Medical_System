package com.feng.medical.conversation;

import java.util.UUID;

/** Answer identity resolved through the owning conversation, never supplied by the browser. */
public record AnswerTarget(UUID answerId, UUID runId) {
}

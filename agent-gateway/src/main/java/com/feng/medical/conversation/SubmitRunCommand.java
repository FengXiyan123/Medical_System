package com.feng.medical.conversation;

import java.util.List;

public record SubmitRunCommand(String question, RunMode mode, List<String> selectedKnowledgeBaseIds,
                               String idempotencyKey) {
}

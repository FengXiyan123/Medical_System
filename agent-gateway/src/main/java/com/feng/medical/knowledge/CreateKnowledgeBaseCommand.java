package com.feng.medical.knowledge;

import java.util.List;
import java.util.UUID;

public record CreateKnowledgeBaseCommand(String name, String description, KnowledgeAccessScope accessScope,
                                         List<UUID> assignedUserIds) {
}

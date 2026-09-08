package com.feng.medical.knowledge;

import com.feng.medical.conversation.AgentRun;
import java.util.List;
import java.util.UUID;

interface RunScopeRepository {
    AgentRun findById(UUID runId);
    List<String> selectedKnowledgeBaseIds(UUID runId);
}

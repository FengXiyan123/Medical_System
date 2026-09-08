package com.feng.medical.conversation;

import java.util.List;

public interface RunDispatchPort {
    void enqueue(AgentRun run, List<String> selectedKnowledgeBaseIds);
}

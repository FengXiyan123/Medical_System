package com.feng.medical.knowledge;

import java.util.List;

public record RetrievalTestCommand(String query, ResolvedKnowledgeScope scope,
                                   List<String> requestedKnowledgeBaseIds, int contextTokenLimit) {
    public RetrievalTestCommand {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("检索问题不能为空");
        requestedKnowledgeBaseIds = List.copyOf(requestedKnowledgeBaseIds);
        if (contextTokenLimit < 1 || contextTokenLimit > 6000) {
            throw new IllegalArgumentException("上下文 Token 上限必须在 1 到 6000 之间");
        }
    }
}

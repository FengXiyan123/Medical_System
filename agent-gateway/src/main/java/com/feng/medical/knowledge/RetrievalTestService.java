package com.feng.medical.knowledge;

import com.feng.medical.conversation.RunMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetrievalTestService {
    private final KnowledgeScopeService scopes;
    private final RetrievalTestPort core;

    RetrievalTestService(KnowledgeScopeService scopes, RetrievalTestPort core) {
        this.scopes = scopes; this.core = core;
    }

    @Transactional(readOnly = true)
    public RetrievalTestResult execute(UUID userId, String query, RunMode mode,
                                       List<UUID> knowledgeBaseIds, Integer contextTokenLimit) {
        UUID testId = UUID.randomUUID();
        ResolvedKnowledgeScope scope = scopes.resolveRetrievalTest(testId, userId, mode, knowledgeBaseIds);
        return core.execute(new RetrievalTestCommand(query, scope,
                scope.knowledgeBaseIds().stream().map(UUID::toString).toList(),
                contextTokenLimit == null ? 6000 : contextTokenLimit));
    }
}

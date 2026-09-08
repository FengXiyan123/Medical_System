package com.feng.medical.knowledge;

import com.feng.medical.conversation.AgentRun;
import com.feng.medical.conversation.RunMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves fresh authorization at every retrieval/read boundary. */
@Service
public class KnowledgeScopeService {
    private final RunScopeRepository runs;
    private final KnowledgeScopeRepository knowledge;

    @Autowired
    KnowledgeScopeService(RunScopeRepository runs, KnowledgeScopeRepository knowledge) {
        this.runs = runs;
        this.knowledge = knowledge;
    }

    /* Package-visible constructor keeps the small domain unit tests independent of JDBC. */
    KnowledgeScopeService(java.util.function.Function<UUID, AgentRun> findRun,
                          java.util.function.Function<UUID, List<String>> selected,
                          java.util.function.Function<UUID, List<ActiveKnowledgeGeneration>> active) {
        this(new RunScopeRepository() {
            @Override public AgentRun findById(UUID runId) { return findRun.apply(runId); }
            @Override public List<String> selectedKnowledgeBaseIds(UUID runId) { return selected.apply(runId); }
        }, active::apply);
    }

    @Transactional(readOnly = true)
    public ResolvedKnowledgeScope resolve(KnowledgeScopeRequest request) {
        if (request == null || request.runId() == null || request.userId() == null || request.mode() == null) {
            throw new IllegalArgumentException("运行范围请求不完整");
        }
        AgentRun run = runs.findById(request.runId());
        if (run == null || !run.userId().equals(request.userId()) || run.mode() != request.mode()) {
            throw new KnowledgeScopeForbiddenException();
        }
        List<ActiveKnowledgeGeneration> allAccessible = knowledge.findActiveAccessibleGenerations(run.userId());
        Set<UUID> currentlyAuthorized = new LinkedHashSet<>(knowledge.findAccessibleKnowledgeBaseIds(run.userId()));
        Set<UUID> requested = unique(request.requestedKnowledgeBaseIds());
        Set<UUID> target;
        if (run.mode() == RunMode.MANUAL_KB) {
            Set<UUID> selected = selectedFor(run.id());
            if (selected.isEmpty()) throw new KnowledgeSelectionRequiredException();
            if (!requested.isEmpty() && !requested.equals(selected)) throw new KnowledgeScopeForbiddenException();
            target = selected;
        } else {
            target = requested.isEmpty() ? currentlyAuthorized : requested;
        }
        if (!currentlyAuthorized.containsAll(target)) throw new KnowledgeScopeForbiddenException();
        List<ActiveKnowledgeGeneration> generations = allAccessible.stream()
                .filter(value -> target.contains(value.knowledgeBaseId())).toList();
        return new ResolvedKnowledgeScope(run.id(), run.userId(), run.mode(), List.copyOf(target), generations);
    }

    /**
     * Builds an ephemeral, authorization-bound scope for an administrator's
     * retrieval experiment.  It never reads a conversation or expands a
     * browser-selected knowledge base beyond the administrator's current access.
     */
    @Transactional(readOnly = true)
    public ResolvedKnowledgeScope resolveRetrievalTest(UUID testId, UUID userId, RunMode mode,
                                                        List<UUID> requestedKnowledgeBaseIds) {
        if (testId == null || userId == null || mode == null) {
            throw new IllegalArgumentException("检索测试范围请求不完整");
        }
        List<ActiveKnowledgeGeneration> accessible = knowledge.findActiveAccessibleGenerations(userId);
        Set<UUID> authorized = new LinkedHashSet<>(knowledge.findAccessibleKnowledgeBaseIds(userId));
        Set<UUID> requested = unique(requestedKnowledgeBaseIds);
        Set<UUID> target = requested.isEmpty() ? authorized : requested;
        if (!authorized.containsAll(target)) throw new KnowledgeScopeForbiddenException();
        if (mode == RunMode.MANUAL_KB && target.isEmpty()) throw new KnowledgeSelectionRequiredException();
        return new ResolvedKnowledgeScope(testId, userId, mode, List.copyOf(target), accessible.stream()
                .filter(generation -> target.contains(generation.knowledgeBaseId())).toList());
    }

    private Set<UUID> selectedFor(UUID runId) {
        try {
            return runs.selectedKnowledgeBaseIds(runId).stream().map(UUID::fromString)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("运行保存了无效的知识库标识", error);
        }
    }

    private Set<UUID> unique(List<UUID> values) {
        if (values == null || values.isEmpty()) return Set.of();
        if (values.stream().anyMatch(java.util.Objects::isNull)) throw new KnowledgeScopeForbiddenException();
        LinkedHashSet<UUID> result = new LinkedHashSet<>(values);
        if (result.size() != values.size()) throw new KnowledgeScopeForbiddenException();
        return result;
    }
}

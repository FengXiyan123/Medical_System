"""Automatic knowledge routing composed with the fixed M3 RAG graph."""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, replace
from typing import Protocol

from medical_agent.graph.budget import RunBudget
from medical_agent.graph.routing import AutoKnowledgeRoute, AutoKnowledgeRouter
from medical_agent.graph.state import (
    AnswerDelta,
    RagEvidence,
    RagFinishReason,
    RagNode,
    RagRunState,
)
from medical_agent.retrieval.pipeline import ContextItem, RetrievalPipeline
from medical_agent.retrieval.scope import ResolvedKnowledgeScope


class RagRunner(Protocol):
    def run(self, state: RagRunState, *, budget: RunBudget) -> RagRunState: ...


class RagFactory(Protocol):
    def __call__(self, retrieve: Callable[[str], Sequence[Mapping[str, object]]]) -> RagRunner: ...


@dataclass(frozen=True, slots=True)
class AutoRagResult:
    """Execution result retaining the route audit beside the standard RAG state."""

    state: RagRunState
    route: AutoKnowledgeRoute


class AutoRagGraph:
    """Run automatic selection, then delegate evidence and answer safety to M3.

    The M3 pipeline receives the model-selected ids only after the router has
    validated them against the immutable gateway scope.  The fixed ``RagGraph``
    remains responsible for answer generation and its insufficient-evidence
    guard, which makes automatic and manual knowledge modes share that safety
    behavior.
    """

    def __init__(
        self,
        *,
        router: AutoKnowledgeRouter,
        pipeline: RetrievalPipeline,
        rag_factory: RagFactory,
        context_token_limit: int = 6000,
    ) -> None:
        if context_token_limit < 1:
            raise ValueError("context_token_limit must be positive")
        self._router = router
        self._pipeline = pipeline
        self._rag_factory = rag_factory
        self._context_token_limit = context_token_limit

    def run(
        self, state: RagRunState, *, scope: ResolvedKnowledgeScope, budget: RunBudget
    ) -> AutoRagResult:
        route = self._router.route(question=state.question, scope=scope)
        if route.direct_answer is not None:
            state.answer = route.direct_answer
            state.answer_deltas = (
                AnswerDelta(node_name=RagNode.DIRECT_ANSWER.value, text=route.direct_answer),
            )
            state.node_path = (*state.node_path, RagNode.DIRECT_ANSWER)
            state.finish_reason = RagFinishReason.COMPLETED
            return AutoRagResult(state=state, route=route)

        def retrieve(query: str) -> tuple[Mapping[str, object], ...]:
            nonlocal route
            result = self._pipeline.retrieve(
                scope=scope,
                query=query,
                context_token_limit=self._context_token_limit,
                requested_knowledge_base_ids=route.requested_knowledge_base_ids,
            )
            if self._should_retry_authorized_scope(route, result.context):
                result = self._pipeline.retrieve(
                    scope=scope,
                    query=query,
                    context_token_limit=self._context_token_limit,
                    requested_knowledge_base_ids=(),
                )
                route = AutoKnowledgeRoute(
                    requested_knowledge_base_ids=route.requested_knowledge_base_ids,
                    direct_answer=route.direct_answer,
                    trace=replace(route.trace, used_authorized_scope_fallback=True),
                )
            return tuple(_as_rag_evidence(item, rank) for rank, item in enumerate(result.context, 1))

        result = self._rag_factory(retrieve).run(state, budget=budget)
        return AutoRagResult(state=result, route=route)

    @staticmethod
    def _should_retry_authorized_scope(
        route: AutoKnowledgeRoute, context: Sequence[ContextItem]
    ) -> bool:
        """Expand a narrow automatic route once after an empty first retrieval.

        Empty model output already starts with the full authorized scope, and a
        selected set equal to every candidate cannot be broadened.  In both
        cases a second identical database query would add latency without
        producing new evidence.
        """

        if context or route.trace.used_authorized_scope_fallback:
            return False
        if not route.requested_knowledge_base_ids:
            return False
        return set(route.requested_knowledge_base_ids) != set(
            route.trace.candidate_knowledge_base_ids
        )


def _as_rag_evidence(item: ContextItem, rank: int) -> Mapping[str, object]:
    """Translate M3's citation-ready context into the fixed RAG graph input."""

    evidence = RagEvidence(
        citation_id=item.citation_id,
        chunk_id=item.chunk_id,
        knowledge_base_id=item.knowledge_base_id,
        generation_id=item.generation_id,
        content_hash=item.content_hash,
        text=item.content,
        token_count=item.token_count,
        score=1 / rank,
    )
    return {
        "citation_id": evidence.citation_id,
        "chunk_id": evidence.chunk_id,
        "knowledge_base_id": evidence.knowledge_base_id,
        "generation_id": evidence.generation_id,
        "content_hash": evidence.content_hash,
        "text": evidence.text,
        "token_count": evidence.token_count,
        "score": evidence.score,
    }

"""The fixed, auditable RAG execution graph used by manual knowledge mode.

This graph keeps retrieval as an injected boundary: scope enforcement and SQL
live in ``medical_agent.retrieval`` while this module owns node order, model
instrumentation, checkpoint snapshots and the safe final-answer gate.
"""

from __future__ import annotations

import re
from collections.abc import Callable, Mapping, Sequence

from medical_agent.graph.budget import InvocationPurpose, RunBudget
from medical_agent.graph.state import (
    AnswerDelta,
    InMemoryRagCheckpointStore,
    RagEvidence,
    RagFinishReason,
    RagNode,
    RagRunState,
)
from medical_agent.models.instrumented import InstrumentedChatModel, InvocationBudgetExceeded
from medical_agent.tracing.citations import CitationValidationError, CitationValidator
from medical_agent.tracing.knowledge import KnowledgeTrace, RetrievedChunk
from medical_agent.tracing.spans import SpanRecorder, SpanStatus

_CITATION_PATTERN = re.compile(r"\[citation:([A-Za-z0-9_.:-]+)]")
_INSUFFICIENT_EVIDENCE_MESSAGE = "现有知识库中没有足够依据回答这个问题。"


class RagGraph:
    """Runs rewrite → retrieve → context → answer → citation_validate exactly once.

    ``answer_deltas`` are appended only after citations pass validation.  This
    prevents UI code from making an unverified internal rewrite or a rejected
    answer visible to a user.
    """

    def __init__(
        self,
        *,
        chat: InstrumentedChatModel,
        retrieve: Callable[[str], Sequence[RagEvidence | Mapping[str, object]]],
        context_token_limit: int,
        checkpoint_store: InMemoryRagCheckpointStore | None = None,
        spans: SpanRecorder | None = None,
    ) -> None:
        if context_token_limit < 1:
            raise ValueError("context_token_limit must be positive")
        self._chat = chat
        self._retrieve = retrieve
        self._context_token_limit = context_token_limit
        self._checkpoints = checkpoint_store or InMemoryRagCheckpointStore()
        self._spans = spans or chat.spans
        self._citation_validator = CitationValidator()

    @property
    def checkpoints(self) -> InMemoryRagCheckpointStore:
        return self._checkpoints

    def run(self, state: RagRunState, *, budget: RunBudget) -> RagRunState:
        run_span = self._spans.start(
            trace_id=state.trace_id or state.run_id,
            node_name="rag_run",
            kind="RUN",
        )
        try:
            self._rewrite(state, run_span.span_id, budget)
            self._retrieve_evidence(state, run_span.span_id)
            self._build_context(state, run_span.span_id)
            if not state.context_items:
                self._mark_insufficient_evidence(state, run_span.span_id)
                self._spans.finish(run_span.span_id)
                return state
            self._answer(state, run_span.span_id, budget)
            self._validate_citations(state, run_span.span_id)
            if state.finish_reason is None:
                state.finish_reason = RagFinishReason.COMPLETED
            self._checkpoints.save(state)
            self._spans.finish(run_span.span_id)
            return state
        except InvocationBudgetExceeded as error:
            state.finish_reason = RagFinishReason.BUDGET_EXHAUSTED
            state.error = str(error)
            self._checkpoints.save(state)
            self._spans.finish(run_span.span_id, status=SpanStatus.FAILED, error_code="BUDGET_EXHAUSTED")
            return state
        except Exception as error:  # noqa: BLE001 - graph failures must become a durable result.
            state.finish_reason = RagFinishReason.FAILED
            state.error = str(error)
            self._checkpoints.save(state)
            self._spans.finish(run_span.span_id, status=SpanStatus.FAILED, error_code="RAG_NODE_FAILED")
            return state

    def _rewrite(self, state: RagRunState, parent_span_id: str, budget: RunBudget) -> None:
        def work(node_span_id: str) -> None:
            result = self._chat.invoke(
                purpose=InvocationPurpose.QUERY_REWRITE,
                request={
                    "messages": [
                        {
                            "role": "system",
                            "content": "在不改变医学术语、数字和限定条件的前提下改写检索问题。",
                        },
                        {"role": "user", "content": state.question},
                    ]
                },
                parent_span_id=node_span_id,
                budget=budget,
                planned_chat_tokens=20,
            )
            state.rewritten_query = result.content.strip() or state.question

        self._run_node(state, RagNode.REWRITE, parent_span_id, work)

    def _retrieve_evidence(self, state: RagRunState, parent_span_id: str) -> None:
        def work(_: str) -> None:
            raw_evidence = self._retrieve(state.rewritten_query or state.question)
            state.retrieved_evidence = tuple(self._coerce_evidence(item) for item in raw_evidence)

        self._run_node(state, RagNode.RETRIEVE, parent_span_id, work)

    def _build_context(self, state: RagRunState, parent_span_id: str) -> None:
        def work(_: str) -> None:
            trace = KnowledgeTrace()
            selected: list[RagEvidence] = []
            used_tokens = 0
            for evidence in state.retrieved_evidence:
                trace.record_retrieved(
                    RetrievedChunk(
                        chunk_id=evidence.chunk_id,
                        knowledge_base_id=evidence.knowledge_base_id,
                        score=evidence.score,
                    )
                )
                if used_tokens + evidence.token_count > self._context_token_limit:
                    continue
                trace.record_selected(evidence.chunk_id, reason="within context token budget")
                selected.append(evidence)
                used_tokens += evidence.token_count
            state.context_items = tuple(selected)

        self._run_node(state, RagNode.CONTEXT, parent_span_id, work)

    def _mark_insufficient_evidence(self, state: RagRunState, parent_span_id: str) -> None:
        def work(_: str) -> None:
            state.answer = _INSUFFICIENT_EVIDENCE_MESSAGE
            state.finish_reason = RagFinishReason.INSUFFICIENT_EVIDENCE

        self._run_node(state, RagNode.INSUFFICIENT_EVIDENCE, parent_span_id, work)

    def _answer(self, state: RagRunState, parent_span_id: str, budget: RunBudget) -> None:
        def work(node_span_id: str) -> None:
            sources = "\n\n".join(
                f"[{item.citation_id}] {item.text}" for item in state.context_items
            )
            result = self._chat.invoke(
                purpose=InvocationPurpose.ANSWER,
                request={
                    "messages": [
                        {
                            "role": "system",
                            "content": "仅根据给定资料作答；引用使用 [citation:引用ID]。",
                        },
                        {
                            "role": "user",
                            "content": f"问题：{state.question}\n\n资料：\n{sources}",
                        },
                    ]
                },
                parent_span_id=node_span_id,
                budget=budget,
                planned_chat_tokens=20,
            )
            state.answer = result.content.strip()
            state.cited_citation_ids = self._cited_ids(state.answer, state.context_items)

        self._run_node(state, RagNode.ANSWER, parent_span_id, work)

    def _validate_citations(self, state: RagRunState, parent_span_id: str) -> None:
        def work(_: str) -> None:
            try:
                state.validated_citations = self._citation_validator.validate(
                    available=tuple(item.citation for item in state.context_items),
                    cited_citation_ids=state.cited_citation_ids,
                )
            except CitationValidationError as error:
                state.answer = None
                state.cited_citation_ids = ()
                state.finish_reason = RagFinishReason.CITATION_INVALID
                state.error = str(error)
                return
            state.answer_deltas = (AnswerDelta(node_name=RagNode.ANSWER.value, text=state.answer or ""),)

        self._run_node(state, RagNode.CITATION_VALIDATE, parent_span_id, work)

    def _run_node(
        self,
        state: RagRunState,
        node: RagNode,
        parent_span_id: str,
        work: Callable[[str], None],
    ) -> None:
        span = self._spans.start(
            trace_id=state.trace_id or state.run_id,
            parent_span_id=parent_span_id,
            node_name=node.value,
            kind="GRAPH_NODE",
        )
        try:
            work(span.span_id)
        except Exception:
            self._spans.finish(span.span_id, status=SpanStatus.FAILED, error_code="NODE_FAILED")
            raise
        else:
            state.node_path = (*state.node_path, node)
            self._checkpoints.save(state)
            self._spans.finish(span.span_id)

    @staticmethod
    def _coerce_evidence(value: RagEvidence | Mapping[str, object]) -> RagEvidence:
        if isinstance(value, RagEvidence):
            return value
        return RagEvidence(
            citation_id=str(value["citation_id"]),
            chunk_id=str(value["chunk_id"]),
            knowledge_base_id=str(value["knowledge_base_id"]),
            generation_id=str(value["generation_id"]),
            content_hash=str(value["content_hash"]),
            text=str(value["text"]),
            token_count=int(value["token_count"]),
            score=float(value["score"]),
        )

    @staticmethod
    def _cited_ids(answer: str, context_items: tuple[RagEvidence, ...]) -> tuple[str, ...]:
        explicit = tuple(dict.fromkeys(_CITATION_PATTERN.findall(answer)))
        if explicit:
            return explicit
        return tuple(item.citation_id for item in context_items)

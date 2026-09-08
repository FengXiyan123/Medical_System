"""Minimal serializable state used by the fixed RAG graph."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum

from medical_agent.tracing.citations import Citation


class RagNode(StrEnum):
    DIRECT_ANSWER = "direct_answer"
    REWRITE = "rewrite"
    RETRIEVE = "retrieve"
    CONTEXT = "context"
    ANSWER = "answer"
    CITATION_VALIDATE = "citation_validate"
    INSUFFICIENT_EVIDENCE = "insufficient_evidence"


class RagFinishReason(StrEnum):
    COMPLETED = "COMPLETED"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    CITATION_INVALID = "CITATION_INVALID"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class RagEvidence:
    citation_id: str
    chunk_id: str
    knowledge_base_id: str
    generation_id: str
    content_hash: str
    text: str
    token_count: int
    score: float

    def __post_init__(self) -> None:
        if not all(
            value.strip()
            for value in (
                self.citation_id,
                self.chunk_id,
                self.knowledge_base_id,
                self.generation_id,
                self.content_hash,
                self.text,
            )
        ):
            raise ValueError("evidence identity and text must not be blank")
        if self.token_count < 1:
            raise ValueError("token_count must be positive")
        if not 0 <= self.score <= 1:
            raise ValueError("score must be between 0 and 1")

    @property
    def citation(self) -> Citation:
        return Citation(
            citation_id=self.citation_id,
            chunk_id=self.chunk_id,
            generation_id=self.generation_id,
            content_hash=self.content_hash,
        )


@dataclass(frozen=True, slots=True)
class AnswerDelta:
    node_name: str
    text: str


@dataclass(slots=True)
class RagRunState:
    run_id: str
    question: str
    trace_id: str | None = None
    history_messages: tuple[str, ...] = ()
    rewritten_query: str | None = None
    retrieved_evidence: tuple[RagEvidence, ...] = ()
    context_items: tuple[RagEvidence, ...] = ()
    answer: str | None = None
    cited_citation_ids: tuple[str, ...] = ()
    validated_citations: tuple[Citation, ...] = ()
    answer_deltas: tuple[AnswerDelta, ...] = ()
    node_path: tuple[RagNode, ...] = ()
    finish_reason: RagFinishReason | None = None
    error: str | None = None

    def __post_init__(self) -> None:
        if not self.run_id.strip():
            raise ValueError("run_id must not be blank")
        if not self.question.strip():
            raise ValueError("question must not be blank")
        if self.trace_id is None:
            self.trace_id = self.run_id


@dataclass(frozen=True, slots=True)
class RagCheckpoint:
    """A diagnostic snapshot that intentionally excludes retrieved source text."""

    run_id: str
    node_path: tuple[RagNode, ...]
    rewritten_query: str | None
    context_citation_ids: tuple[str, ...]
    answer: str | None
    finish_reason: RagFinishReason | None
    error: str | None

    @classmethod
    def from_state(cls, state: RagRunState) -> RagCheckpoint:
        return cls(
            run_id=state.run_id,
            node_path=state.node_path,
            rewritten_query=state.rewritten_query,
            context_citation_ids=tuple(item.citation_id for item in state.context_items),
            answer=state.answer,
            finish_reason=state.finish_reason,
            error=state.error,
        )


@dataclass(slots=True)
class InMemoryRagCheckpointStore:
    """Reference store keyed by LangGraph-compatible ``thread_id == run_id``."""

    _checkpoints: dict[str, list[RagCheckpoint]] = field(default_factory=dict)

    def save(self, state: RagRunState) -> RagCheckpoint:
        checkpoint = RagCheckpoint.from_state(state)
        self._checkpoints.setdefault(state.run_id, []).append(checkpoint)
        return checkpoint

    def latest(self, *, thread_id: str) -> RagCheckpoint | None:
        entries = self._checkpoints.get(thread_id, ())
        return entries[-1] if entries else None

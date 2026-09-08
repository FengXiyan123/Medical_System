"""Internal retrieval-debug endpoint with the same scope enforcement as a run.

The endpoint deliberately does not invoke the answer model.  Its usage marker is
therefore ``RETRIEVAL_TEST`` instead of a chat purpose, so reporting can keep
administrator experiments separate from user conversations.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel, Field

from medical_agent.contracts.run import RunMode
from medical_agent.ingestion.indexing import DeterministicEmbedder
from medical_agent.persistence.pg_rag import (
    PostgresChunkCatalog,
    PostgresQueryableVectorStore,
    PostgresRagRepository,
)
from medical_agent.retrieval.pipeline import (
    ContextItem,
    EvidenceHit,
    InMemoryChunkCatalog,
    RetrievalPipeline,
)
from medical_agent.retrieval.scope import ResolvedKnowledgeScope
from medical_agent.retrieval.vector_store import InMemoryQueryableVectorStore

RETRIEVAL_TEST_PURPOSE = "RETRIEVAL_TEST"


@dataclass(frozen=True, slots=True)
class RetrievalTestRequest:
    query: str
    mode: RunMode
    requested_knowledge_base_ids: tuple[str, ...]
    scope: dict[str, object]
    context_token_limit: int = 6000

    def __post_init__(self) -> None:
        if not self.query.strip():
            raise ValueError("query must not be blank")
        if self.context_token_limit < 1 or self.context_token_limit > 6000:
            raise ValueError("context token limit must be between 1 and 6000")


@dataclass(frozen=True, slots=True)
class RetrievalTestHit:
    stage: str
    rank: int
    knowledge_base_id: str
    generation_id: str
    chunk_id: str
    raw_score: float
    score_type: str
    text_snapshot: str


@dataclass(frozen=True, slots=True)
class RetrievalTestContext:
    citation_id: str
    knowledge_base_id: str
    generation_id: str
    chunk_id: str
    token_count: int
    content: str


@dataclass(frozen=True, slots=True)
class RetrievalTestResult:
    rewritten_query: str
    recalled: tuple[RetrievalTestHit, ...]
    reranked: tuple[RetrievalTestHit, ...]
    context: tuple[RetrievalTestContext, ...]
    reranker_status: str
    reranked_score_type: str | None
    usage_purpose: str = RETRIEVAL_TEST_PURPOSE


class RetrievalTestService:
    """Runs retrieval only and exposes each persisted evidence stage.

    A reranker is optional in M5.  When absent, the response says so instead of
    manufacturing a score that looks like a reranker output.
    """

    def __init__(self, pipeline: RetrievalPipeline, *, reranker_configured: bool = False) -> None:
        self._pipeline = pipeline
        self._reranker_configured = reranker_configured

    def run(self, request: RetrievalTestRequest) -> RetrievalTestResult:
        scope = ResolvedKnowledgeScope.from_gateway_response(
            request.scope,
            expected_run_id=_string(request.scope, "run_id"),
            expected_user_id=_string(request.scope, "user_id"),
            expected_mode=request.mode,
        )
        result = self._pipeline.retrieve(
            scope=scope,
            query=request.query,
            context_token_limit=request.context_token_limit,
            requested_knowledge_base_ids=request.requested_knowledge_base_ids,
        )
        recalled = tuple(_hit(hit) for hit in result.evidence if hit.stage.value == "RECALL")
        reranked = tuple(_hit(hit) for hit in result.evidence if hit.stage.value == "RERANK")
        return RetrievalTestResult(
            rewritten_query=request.query,
            recalled=recalled,
            reranked=reranked,
            context=tuple(_context(item) for item in result.context),
            reranker_status="CONFIGURED" if self._reranker_configured else "NOT_CONFIGURED",
            reranked_score_type="rrf" if self._reranker_configured and reranked else None,
        )


class RetrievalTestHttpRequest(BaseModel):
    query: str = Field(min_length=1, max_length=10_000)
    mode: RunMode
    requested_knowledge_base_ids: tuple[str, ...] = ()
    scope: dict[str, Any]
    context_token_limit: int = Field(default=6000, ge=1, le=6000)

    def to_domain(self) -> RetrievalTestRequest:
        return RetrievalTestRequest(
            query=self.query,
            mode=self.mode,
            requested_knowledge_base_ids=self.requested_knowledge_base_ids,
            scope=dict(self.scope),
            context_token_limit=self.context_token_limit,
        )


def empty_retrieval_test_service() -> RetrievalTestService:
    """A safe demo default; production replaces it with the pgvector adapter."""

    class _Embedder:
        def embed(self, query: str) -> tuple[float, ...]:
            del query
            return (1.0, 0.0, 0.0)

    vectors = InMemoryQueryableVectorStore(dimension=3)
    return RetrievalTestService(
        RetrievalPipeline(vectors, InMemoryChunkCatalog(()), _Embedder(), profile_id="default")
    )


def postgres_retrieval_test_service(repository: PostgresRagRepository) -> RetrievalTestService:
    """Production retrieval-test service backed by the same pgvector rows as chat."""
    return RetrievalTestService(
        RetrievalPipeline(
            PostgresQueryableVectorStore(repository), PostgresChunkCatalog(repository),
            DeterministicEmbedder(1024, "mock-text-embedding-v4-1024"),
            profile_id="mock-text-embedding-v4-1024",
        )
    )


def _hit(hit: EvidenceHit) -> RetrievalTestHit:
    return RetrievalTestHit(
        stage=hit.stage.value,
        rank=hit.rank,
        knowledge_base_id=hit.knowledge_base_id,
        generation_id=hit.generation_id,
        chunk_id=hit.chunk_id,
        raw_score=hit.raw_score,
        score_type=hit.score_type,
        text_snapshot=hit.text_snapshot,
    )


def _context(item: ContextItem) -> RetrievalTestContext:
    return RetrievalTestContext(
        citation_id=item.citation_id,
        knowledge_base_id=item.knowledge_base_id,
        generation_id=item.generation_id,
        chunk_id=item.chunk_id,
        token_count=item.token_count,
        content=item.content,
    )


def _string(value: dict[str, object], key: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result.strip():
        raise ValueError(f"scope {key} must be a nonblank string")
    return result

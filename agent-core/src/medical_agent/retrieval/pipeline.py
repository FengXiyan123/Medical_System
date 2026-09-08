"""Scope-filtered hybrid retrieval with auditable evidence stages."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Protocol
from uuid import NAMESPACE_URL, uuid5

from medical_agent.retrieval.context import ContextCandidate, fit_context
from medical_agent.retrieval.scope import EffectiveRetrievalScope, ResolvedKnowledgeScope


class QueryEmbedder(Protocol):
    def embed(self, query: str) -> tuple[float, ...]: ...


class QueryableVectorStore(Protocol):
    def search(self, *, generation_ids: set[str], profile_id: str, query: tuple[float, ...],
               top_k: int): ...


class ChunkCatalog(Protocol):
    def for_generations(self, generation_ids: frozenset[str]) -> tuple[RetrievalChunk, ...]: ...

    def get(self, generation_id: str, chunk_id: str) -> RetrievalChunk | None: ...


class EvidenceStage(str, Enum):
    RECALL = "RECALL"
    RERANK = "RERANK"
    CONTEXT = "CONTEXT"


@dataclass(frozen=True, slots=True)
class RetrievalChunk:
    generation_id: str
    knowledge_base_id: str
    chunk_id: str
    content_hash: str
    content: str
    token_count: int

    def __post_init__(self) -> None:
        if not all(
            value.strip()
            for value in (
                self.generation_id,
                self.knowledge_base_id,
                self.chunk_id,
                self.content_hash,
                self.content,
            )
        ):
            raise ValueError("retrieval chunk fields must not be blank")
        if self.token_count < 1:
            raise ValueError("retrieval chunk token count must be positive")


@dataclass(frozen=True, slots=True)
class EvidenceHit:
    stage: EvidenceStage
    rank: int
    generation_id: str
    knowledge_base_id: str
    chunk_id: str
    content_hash: str
    raw_score: float
    score_type: str
    text_snapshot: str


@dataclass(frozen=True, slots=True)
class ContextItem:
    citation_id: str
    generation_id: str
    knowledge_base_id: str
    chunk_id: str
    content_hash: str
    content: str
    token_count: int


@dataclass(frozen=True, slots=True)
class RetrievalResult:
    retrieved: tuple[RetrievalChunk, ...]
    reranked: tuple[RetrievalChunk, ...]
    context: tuple[ContextItem, ...]
    evidence: tuple[EvidenceHit, ...]


class InMemoryChunkCatalog:
    """Content repository boundary; the production adapter reads only scoped PG rows."""

    def __init__(self, chunks: tuple[RetrievalChunk, ...]) -> None:
        keys = [(chunk.generation_id, chunk.chunk_id) for chunk in chunks]
        if len(set(keys)) != len(keys):
            raise ValueError("chunk catalog contains duplicate generation/chunk ids")
        self._chunks = {(chunk.generation_id, chunk.chunk_id): chunk for chunk in chunks}

    def for_generations(self, generation_ids: frozenset[str]) -> tuple[RetrievalChunk, ...]:
        return tuple(
            chunk
            for chunk in self._chunks.values()
            if chunk.generation_id in generation_ids
        )

    def get(self, generation_id: str, chunk_id: str) -> RetrievalChunk | None:
        return self._chunks.get((generation_id, chunk_id))


class RetrievalPipeline:
    """Retrieves from the database scope first, never filters unauthorized hits later."""

    def __init__(
        self,
        vectors: QueryableVectorStore,
        catalog: ChunkCatalog,
        embedder: QueryEmbedder,
        *,
        profile_id: str,
        candidate_k: int = 20,
        rerank_k: int = 6,
    ) -> None:
        if not profile_id.strip():
            raise ValueError("profile_id must not be blank")
        if candidate_k < 1 or rerank_k < 1 or rerank_k > candidate_k:
            raise ValueError("retrieval limits are invalid")
        self._vectors = vectors
        self._catalog = catalog
        self._embedder = embedder
        self._profile_id = profile_id
        self._candidate_k = candidate_k
        self._rerank_k = rerank_k

    def retrieve(
        self,
        *,
        scope: ResolvedKnowledgeScope,
        query: str,
        context_token_limit: int = 6000,
        requested_knowledge_base_ids: tuple[str, ...] = (),
    ) -> RetrievalResult:
        if not query.strip():
            raise ValueError("query must not be blank")
        effective_scope = scope.require_retrieval_scope(
            requested_knowledge_base_ids=requested_knowledge_base_ids
        )
        # This guard intentionally happens before query embedding/model activity.
        if not effective_scope.generation_ids:
            return RetrievalResult((), (), (), ())

        query_vector = self._embedder.embed(query)
        vector_hits = self._vectors.search(
            generation_ids=set(effective_scope.generation_ids),
            profile_id=self._profile_id,
            query=query_vector,
            top_k=self._candidate_k,
        )
        catalog_chunks = self._catalog.for_generations(effective_scope.generation_ids)
        by_key = {(chunk.generation_id, chunk.chunk_id): chunk for chunk in catalog_chunks}
        ranked_vector = tuple(
            by_key[(hit.generation_id, hit.chunk_id)]
            for hit in vector_hits
            if (hit.generation_id, hit.chunk_id) in by_key
        )
        ranked_keyword = _keyword_rank(query, catalog_chunks, self._candidate_k)
        candidates = _fuse_rankings(ranked_vector, ranked_keyword, self._candidate_k)
        evidence = _recall_evidence(candidates, ranked_vector, ranked_keyword)
        reranked = candidates[: self._rerank_k]
        evidence += _stage_evidence(EvidenceStage.RERANK, reranked, score_type="rrf")

        selected_ids = set(
            fit_context(
                (ContextCandidate(chunk.chunk_id, chunk.token_count) for chunk in reranked),
                token_limit=context_token_limit,
            )
        )
        context_chunks = tuple(chunk for chunk in reranked if chunk.chunk_id in selected_ids)
        context = tuple(_context_item(effective_scope, chunk) for chunk in context_chunks)
        evidence += _stage_evidence(EvidenceStage.CONTEXT, context_chunks, score_type="context")
        return RetrievalResult(candidates, reranked, context, evidence)

    def read(
        self,
        *,
        scope: ResolvedKnowledgeScope,
        generation_id: str,
        chunk_id: str,
        requested_knowledge_base_ids: tuple[str, ...] = (),
    ) -> RetrievalChunk:
        """Read a known chunk only after re-evaluating the current gateway scope."""

        effective_scope = scope.require_retrieval_scope(
            requested_knowledge_base_ids=requested_knowledge_base_ids
        )
        effective_scope.require_generation(generation_id)
        chunk = self._catalog.get(generation_id, chunk_id)
        if chunk is None:
            raise ValueError("chunk does not exist")
        return chunk


def _keyword_rank(
    query: str, chunks: tuple[RetrievalChunk, ...], limit: int
) -> tuple[RetrievalChunk, ...]:
    normalized = query.casefold().strip()
    terms = tuple(term for term in normalized.split() if term) or (normalized,)
    scored = [
        (
            sum(chunk.content.casefold().count(term) for term in terms),
            chunk,
        )
        for chunk in chunks
    ]
    return tuple(
        chunk
        for score, chunk in sorted(scored, key=lambda value: (-value[0], value[1].chunk_id))
        if score > 0
    )[:limit]


def _fuse_rankings(
    vector: tuple[RetrievalChunk, ...], keyword: tuple[RetrievalChunk, ...], limit: int
) -> tuple[RetrievalChunk, ...]:
    ranks: dict[tuple[str, str], tuple[RetrievalChunk, int, int]] = {}
    for rank, chunk in enumerate(vector, start=1):
        ranks[(chunk.generation_id, chunk.chunk_id)] = (chunk, rank, 0)
    for rank, chunk in enumerate(keyword, start=1):
        current = ranks.get((chunk.generation_id, chunk.chunk_id))
        if current is None:
            ranks[(chunk.generation_id, chunk.chunk_id)] = (chunk, 0, rank)
        else:
            ranks[(chunk.generation_id, chunk.chunk_id)] = (chunk, current[1], rank)

    def rrf(value: tuple[RetrievalChunk, int, int]) -> float:
        _, vector_rank, keyword_rank = value
        return (1 / (60 + vector_rank) if vector_rank else 0) + (
            1 / (60 + keyword_rank) if keyword_rank else 0
        )

    return tuple(
        value[0]
        for value in sorted(ranks.values(), key=lambda value: (-rrf(value), value[0].chunk_id))[:limit]
    )


def _recall_evidence(
    candidates: tuple[RetrievalChunk, ...],
    vector: tuple[RetrievalChunk, ...],
    keyword: tuple[RetrievalChunk, ...],
) -> tuple[EvidenceHit, ...]:
    vector_ranks = {(chunk.generation_id, chunk.chunk_id): rank for rank, chunk in enumerate(vector, 1)}
    keyword_ranks = {(chunk.generation_id, chunk.chunk_id): rank for rank, chunk in enumerate(keyword, 1)}
    result: list[EvidenceHit] = []
    for rank, chunk in enumerate(candidates, 1):
        vector_rank = vector_ranks.get((chunk.generation_id, chunk.chunk_id), 0)
        keyword_rank = keyword_ranks.get((chunk.generation_id, chunk.chunk_id), 0)
        rrf = (1 / (60 + vector_rank) if vector_rank else 0) + (
            1 / (60 + keyword_rank) if keyword_rank else 0
        )
        result.append(
            EvidenceHit(
                EvidenceStage.RECALL,
                rank,
                chunk.generation_id,
                chunk.knowledge_base_id,
                chunk.chunk_id,
                chunk.content_hash,
                rrf,
                "hybrid_rrf",
                chunk.content,
            )
        )
    return tuple(result)


def _stage_evidence(
    stage: EvidenceStage, chunks: tuple[RetrievalChunk, ...], *, score_type: str
) -> tuple[EvidenceHit, ...]:
    return tuple(
        EvidenceHit(
            stage,
            rank,
            chunk.generation_id,
            chunk.knowledge_base_id,
            chunk.chunk_id,
            chunk.content_hash,
            1 / rank,
            score_type,
            chunk.content,
        )
        for rank, chunk in enumerate(chunks, 1)
    )


def _context_item(scope: EffectiveRetrievalScope, chunk: RetrievalChunk) -> ContextItem:
    citation_id = str(
        uuid5(
            NAMESPACE_URL,
            f"medical-agent:{scope.run_id}:{chunk.generation_id}:{chunk.chunk_id}:{chunk.content_hash}",
        )
    )
    return ContextItem(
        citation_id=citation_id,
        generation_id=chunk.generation_id,
        knowledge_base_id=chunk.knowledge_base_id,
        chunk_id=chunk.chunk_id,
        content_hash=chunk.content_hash,
        content=chunk.content,
        token_count=chunk.token_count,
    )

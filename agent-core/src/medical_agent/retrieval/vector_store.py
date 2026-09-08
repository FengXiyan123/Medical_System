"""Queryable vector-store contract that keeps generation and profile filters mandatory."""

from __future__ import annotations

from dataclasses import dataclass
from math import sqrt

from medical_agent.ingestion.indexing import InMemoryVectorStore


@dataclass(frozen=True)
class VectorHit:
    generation_id: str
    chunk_id: str
    score: float


class InMemoryQueryableVectorStore(InMemoryVectorStore):
    """Demo adapter matching the pgvector query contract without exposing inactive generations."""

    def search(
        self, *, generation_ids: set[str], profile_id: str, query: tuple[float, ...], top_k: int
    ) -> tuple[VectorHit, ...]:
        if not generation_ids:
            return ()
        if top_k < 1:
            raise ValueError("top_k must be positive")
        if len(query) != self.dimension:
            raise ValueError("query dimension does not match the configured vector index")
        query_norm = sqrt(sum(value * value for value in query))
        if query_norm == 0:
            raise ValueError("query vector must not be zero")
        hits = []
        for record in self.records_for(generation_ids=generation_ids, profile_id=profile_id):
            vector_norm = sqrt(sum(value * value for value in record.vector))
            if vector_norm == 0:
                continue
            score = sum(left * right for left, right in zip(query, record.vector, strict=True)) / (query_norm * vector_norm)
            hits.append(VectorHit(record.generation_id, record.chunk_id, score))
        return tuple(sorted(hits, key=lambda hit: (-hit.score, hit.chunk_id))[:top_k])

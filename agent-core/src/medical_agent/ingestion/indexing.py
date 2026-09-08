"""Index a frozen draft generation without mixing embedding profiles or dimensions."""

from __future__ import annotations

import json
from dataclasses import dataclass
from hashlib import sha256
from typing import Protocol

from medical_agent.ingestion.draft_editor import DraftChunk, DraftGeneration


class GenerationNotReadyError(ValueError):
    """The generation cannot be indexed or published in its current state."""


class Embedder(Protocol):
    profile_id: str
    dimension: int

    def embed(self, content: str) -> tuple[float, ...]: ...


@dataclass(frozen=True)
class IndexedEmbedding:
    generation_id: str
    chunk_id: str
    profile_id: str
    content_hash: str
    vector: tuple[float, ...]


class InMemoryVectorStore:
    """A contract-focused replacement for the pgvector repository in unit tests."""

    def __init__(self, *, dimension: int) -> None:
        if dimension < 1:
            raise ValueError("dimension must be positive")
        self.dimension = dimension
        self._records: dict[tuple[str, str, str], IndexedEmbedding] = {}

    @property
    def embedding_count(self) -> int:
        return len(self._records)

    def upsert(self, record: IndexedEmbedding) -> bool:
        if len(record.vector) != self.dimension:
            raise ValueError("embedding dimension does not match the configured vector index")
        key = (record.generation_id, record.chunk_id, record.profile_id)
        existing = self._records.get(key)
        if existing is not None and existing.content_hash == record.content_hash:
            return False
        self._records[key] = record
        return True

    def has_matching(self, *, generation_id: str, chunk: DraftChunk, profile_id: str) -> bool:
        record = self._records.get((generation_id, chunk.id, profile_id))
        return record is not None and record.content_hash == chunk.content_hash

    def records_for(self, *, generation_ids: set[str], profile_id: str) -> tuple[IndexedEmbedding, ...]:
        return tuple(
            record for record in self._records.values()
            if record.generation_id in generation_ids and record.profile_id == profile_id
        )


@dataclass
class DeterministicEmbedder:
    """Explicit mock embedding model for local teaching/demo flows."""

    dimension: int
    profile_id: str
    calls: int = 0

    def embed(self, content: str) -> tuple[float, ...]:
        self.calls += 1
        digest = sha256(content.encode("utf-8")).digest()
        return tuple(digest[index % len(digest)] / 255 for index in range(self.dimension))


@dataclass(frozen=True)
class IndexBuildResult:
    generation_id: str
    document_id: str | None
    state: str
    embedding_profile_id: str
    chunk_count: int
    manifest_hash: str


def build_generation_index(
    generation: DraftGeneration, *, embedder: Embedder, store: InMemoryVectorStore
) -> IndexBuildResult:
    if generation.state != "DRAFT":
        raise GenerationNotReadyError("only an unfrozen draft generation can be indexed")
    if embedder.dimension != store.dimension:
        raise ValueError("embedding dimension does not match the configured vector index")
    enabled_chunks = tuple(chunk for chunk in generation.chunks if chunk.enabled)
    if not enabled_chunks:
        raise GenerationNotReadyError("at least one enabled chunk is required before indexing")
    for chunk in enabled_chunks:
        if not store.has_matching(generation_id=generation.id, chunk=chunk, profile_id=embedder.profile_id):
            store.upsert(
                IndexedEmbedding(
                    generation_id=generation.id,
                    chunk_id=chunk.id,
                    profile_id=embedder.profile_id,
                    content_hash=chunk.content_hash,
                    vector=embedder.embed(chunk.content),
                )
            )
    if not all(store.has_matching(generation_id=generation.id, chunk=chunk, profile_id=embedder.profile_id)
               for chunk in enabled_chunks):
        raise GenerationNotReadyError("not every enabled chunk has a valid embedding")
    manifest = {
        "generation_id": generation.id,
        "embedding_profile_id": embedder.profile_id,
        "dimension": embedder.dimension,
        "enabled_chunks": [
            {"id": chunk.id, "ordinal": chunk.ordinal, "content_hash": chunk.content_hash}
            for chunk in enabled_chunks
        ],
    }
    return IndexBuildResult(
        generation_id=generation.id,
        document_id=generation.document_id,
        state="BUILD_READY",
        embedding_profile_id=embedder.profile_id,
        chunk_count=len(enabled_chunks),
        manifest_hash=sha256(json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest(),
    )

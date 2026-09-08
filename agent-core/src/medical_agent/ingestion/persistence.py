"""Persistence boundary for draft chunks and their source locations.

The production PostgreSQL adapter belongs behind this protocol.  Keeping the
boundary explicit prevents parsing workers from writing business tables.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Protocol

from medical_agent.ingestion.chunking import DocumentChunk


@dataclass(frozen=True, slots=True)
class StoredChunk:
    chunk_id: str
    document_version_id: str
    generation: int
    ordinal: int
    title: str
    content: str
    token_count: int
    tokenizer_version: str
    source_page_number: int | None
    source_section_index: int | None
    source_char_start: int | None
    source_char_end: int | None
    source_heading_path: tuple[str, ...]


class ChunkRepository(Protocol):
    def replace_generation(
        self, document_version_id: str, generation: int, chunks: tuple[StoredChunk, ...]
    ) -> None: ...

    def list_generation(self, document_version_id: str, generation: int) -> tuple[StoredChunk, ...]: ...


class InMemoryChunkRepository:
    """Deterministic repository used by service tests and local preview flows."""

    def __init__(self) -> None:
        self._generations: dict[tuple[str, int], tuple[StoredChunk, ...]] = {}

    def replace_generation(
        self, document_version_id: str, generation: int, chunks: tuple[StoredChunk, ...]
    ) -> None:
        self._generations[(document_version_id, generation)] = chunks

    def list_generation(self, document_version_id: str, generation: int) -> tuple[StoredChunk, ...]:
        return self._generations.get((document_version_id, generation), ())


def persist_draft_chunks(
    repository: ChunkRepository,
    *,
    document_version_id: str,
    generation: int,
    chunks: tuple[DocumentChunk, ...],
) -> tuple[StoredChunk, ...]:
    if not document_version_id.strip():
        raise ValueError("document_version_id must not be blank")
    if generation < 1:
        raise ValueError("generation must be positive")
    stored = tuple(
        _to_stored_chunk(document_version_id=document_version_id, generation=generation, chunk=chunk)
        for chunk in chunks
    )
    repository.replace_generation(document_version_id, generation, stored)
    return stored


def _to_stored_chunk(*, document_version_id: str, generation: int, chunk: DocumentChunk) -> StoredChunk:
    source = chunk.source
    fingerprint = f"{document_version_id}:{generation}:{chunk.ordinal}:{chunk.content}".encode()
    return StoredChunk(
        chunk_id=hashlib.sha256(fingerprint).hexdigest(),
        document_version_id=document_version_id,
        generation=generation,
        ordinal=chunk.ordinal,
        title=chunk.title,
        content=chunk.content,
        token_count=chunk.token_count,
        tokenizer_version=chunk.tokenizer_version,
        source_page_number=source.page_number if source else chunk.page_number,
        source_section_index=source.section_index if source else None,
        source_char_start=source.char_start if source else None,
        source_char_end=source.char_end if source else None,
        source_heading_path=source.heading_path if source else (),
    )

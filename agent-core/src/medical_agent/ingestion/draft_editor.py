"""Immutable draft-generation editing with server-side optimistic concurrency.

The database adapter can persist these objects in PostgreSQL, while this module
keeps the editing rules independently testable and usable by the internal API.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, replace
from hashlib import sha256
from typing import Literal
from uuid import uuid4

GenerationState = Literal["DRAFT", "INDEXING", "BUILD_READY", "ACTIVE", "RETIRED"]


class EditConflictError(ValueError):
    """The client edited an out-of-date generation revision."""


class DraftReadOnlyError(ValueError):
    """Only a DRAFT generation may be changed."""


class InvalidChunkOperationError(ValueError):
    """A chunk edit would produce invalid content or topology."""


@dataclass(frozen=True)
class DraftChunk:
    id: str
    ordinal: int
    content: str
    enabled: bool
    page_start: int | None = None
    page_end: int | None = None
    section_path: str | None = None
    manually_edited: bool = False

    def __post_init__(self) -> None:
        if not self.id.strip():
            raise ValueError("chunk id must not be blank")
        if self.ordinal < 0:
            raise ValueError("chunk ordinal must be non-negative")
        if not self.content.strip():
            raise ValueError("chunk content must not be blank")
        if self.page_start is not None and self.page_start < 1:
            raise ValueError("page_start must be positive")
        if self.page_end is not None and self.page_start is not None and self.page_end < self.page_start:
            raise ValueError("page_end must not precede page_start")

    @property
    def content_hash(self) -> str:
        return sha256(self.content.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class DraftGeneration:
    id: str
    state: GenerationState
    edit_revision: int
    chunks: tuple[DraftChunk, ...]
    document_id: str | None = None
    embedding_profile_id: str | None = None
    manifest_hash: str | None = None

    def __post_init__(self) -> None:
        if not self.id.strip():
            raise ValueError("generation id must not be blank")
        if self.edit_revision < 0:
            raise ValueError("edit_revision must be non-negative")
        expected_ordinals = list(range(len(self.chunks)))
        if [chunk.ordinal for chunk in self.chunks] != expected_ordinals:
            raise ValueError("chunk ordinals must be contiguous and ordered")
        if len({chunk.id for chunk in self.chunks}) != len(self.chunks):
            raise ValueError("chunk ids must be unique")


@dataclass(frozen=True)
class ChunkPage:
    items: tuple[DraftChunk, ...]
    total: int
    next_offset: int | None
    edit_revision: int
    generation_state: GenerationState


class DraftEditor:
    """Owns one generation and advances its aggregate edit revision per mutation."""

    def __init__(self, generation: DraftGeneration) -> None:
        self._generation = generation

    @property
    def generation(self) -> DraftGeneration:
        return self._generation

    def list_chunks(
        self, *, query: str | None = None, enabled: bool | None = None, offset: int = 0, limit: int = 50
    ) -> ChunkPage:
        if offset < 0 or limit < 1 or limit > 100:
            raise ValueError("offset and limit are out of range")
        normalized_query = query.strip().casefold() if query and query.strip() else None
        filtered = tuple(
            chunk
            for chunk in self._generation.chunks
            if (enabled is None or chunk.enabled == enabled)
            and (
                normalized_query is None
                or normalized_query in chunk.content.casefold()
                or (chunk.section_path is not None and normalized_query in chunk.section_path.casefold())
            )
        )
        items = filtered[offset : offset + limit]
        next_offset = offset + len(items) if offset + len(items) < len(filtered) else None
        return ChunkPage(items=items, total=len(filtered), next_offset=next_offset,
                         edit_revision=self._generation.edit_revision, generation_state=self._generation.state)

    def get_chunk(self, chunk_id: str) -> DraftChunk:
        return self._require_chunk(chunk_id)

    def update_content(self, chunk_id: str, content: str, *, expected_edit_revision: int) -> DraftGeneration:
        normalized = self._normalized_content(content)
        return self._replace_chunk(
            chunk_id, lambda chunk: replace(chunk, content=normalized, manually_edited=True), expected_edit_revision
        )

    def set_enabled(self, chunk_id: str, enabled: bool, *, expected_edit_revision: int) -> DraftGeneration:
        return self._replace_chunk(
            chunk_id, lambda chunk: replace(chunk, enabled=enabled, manually_edited=True), expected_edit_revision
        )

    def split(self, chunk_id: str, *, at_character: int, expected_edit_revision: int) -> DraftGeneration:
        self._require_writable_revision(expected_edit_revision)
        chunk = self._require_chunk(chunk_id)
        if at_character <= 0 or at_character >= len(chunk.content):
            raise InvalidChunkOperationError("split position must be inside the chunk content")
        left = self._normalized_content(chunk.content[:at_character])
        right = self._normalized_content(chunk.content[at_character:])
        position = self._generation.chunks.index(chunk)
        chunks = list(self._generation.chunks)
        chunks[position : position + 1] = [
            replace(chunk, content=left, manually_edited=True),
            DraftChunk(
                id=str(uuid4()), ordinal=chunk.ordinal + 1, content=right, enabled=chunk.enabled,
                page_start=chunk.page_start, page_end=chunk.page_end, section_path=chunk.section_path,
                manually_edited=True,
            ),
        ]
        return self._commit(chunks)

    def merge(self, first_chunk_id: str, second_chunk_id: str, *, expected_edit_revision: int) -> DraftGeneration:
        self._require_writable_revision(expected_edit_revision)
        first = self._require_chunk(first_chunk_id)
        second = self._require_chunk(second_chunk_id)
        if second.ordinal != first.ordinal + 1:
            raise InvalidChunkOperationError("only adjacent chunks in the same generation can be merged")
        if first.enabled != second.enabled:
            raise InvalidChunkOperationError("chunks with different enabled states cannot be merged")
        chunks = list(self._generation.chunks)
        index = first.ordinal
        chunks[index : index + 2] = [
            replace(
                first,
                content=self._normalized_content(f"{first.content}\n\n{second.content}"),
                page_end=second.page_end if second.page_end is not None else first.page_end,
                manually_edited=True,
            )
        ]
        return self._commit(chunks)

    def mark_build_ready(self, *, embedding_profile_id: str, manifest_hash: str) -> DraftGeneration:
        if self._generation.state != "DRAFT":
            raise DraftReadOnlyError("only a draft generation can enter the index build state")
        self._generation = replace(
            self._generation,
            state="BUILD_READY",
            embedding_profile_id=embedding_profile_id,
            manifest_hash=manifest_hash,
        )
        return self._generation

    def _replace_chunk(
        self, chunk_id: str, mutate: Callable[[DraftChunk], DraftChunk], expected_edit_revision: int
    ) -> DraftGeneration:
        self._require_writable_revision(expected_edit_revision)
        chunk = self._require_chunk(chunk_id)
        chunks = list(self._generation.chunks)
        chunks[chunk.ordinal] = mutate(chunk)
        return self._commit(chunks)

    def _commit(self, chunks: list[DraftChunk]) -> DraftGeneration:
        normalized = tuple(replace(chunk, ordinal=ordinal) for ordinal, chunk in enumerate(chunks))
        self._generation = replace(
            self._generation,
            edit_revision=self._generation.edit_revision + 1,
            chunks=normalized,
            embedding_profile_id=None,
            manifest_hash=None,
        )
        return self._generation

    def _require_writable_revision(self, expected_edit_revision: int) -> None:
        if self._generation.state != "DRAFT":
            raise DraftReadOnlyError("published or indexed generations are read-only; create a new draft")
        if expected_edit_revision != self._generation.edit_revision:
            raise EditConflictError("generation was changed; refresh before editing")

    def _require_chunk(self, chunk_id: str) -> DraftChunk:
        for chunk in self._generation.chunks:
            if chunk.id == chunk_id:
                return chunk
        raise InvalidChunkOperationError("chunk does not belong to this generation")

    @staticmethod
    def _normalized_content(content: str) -> str:
        normalized = content.strip()
        if not normalized:
            raise InvalidChunkOperationError("chunk content must not be blank")
        return normalized

"""Internal chunk workspace API used by the Java administration gateway."""

from __future__ import annotations

from pydantic import BaseModel, Field, model_validator

from medical_agent.ingestion.draft_editor import (
    ChunkPage,
    DraftChunk,
    DraftEditor,
    DraftGeneration,
    DraftReadOnlyError,
    EditConflictError,
    InvalidChunkOperationError,
)
from medical_agent.ingestion.indexing import (
    DeterministicEmbedder,
    IndexBuildResult,
    InMemoryVectorStore,
    build_generation_index,
)
from medical_agent.persistence.pg_rag import PostgresRagRepository


class ChunkResponse(BaseModel):
    id: str
    ordinal: int
    content: str
    enabled: bool
    page_start: int | None
    page_end: int | None
    section_path: str | None
    manually_edited: bool
    content_hash: str

    @classmethod
    def from_chunk(cls, chunk: DraftChunk) -> ChunkResponse:
        return cls(
            id=chunk.id, ordinal=chunk.ordinal, content=chunk.content, enabled=chunk.enabled,
            page_start=chunk.page_start, page_end=chunk.page_end, section_path=chunk.section_path,
            manually_edited=chunk.manually_edited, content_hash=chunk.content_hash,
        )


class ChunkPageResponse(BaseModel):
    chunks: tuple[ChunkResponse, ...]
    total: int
    next_offset: int | None
    edit_revision: int
    generation_state: str

    @classmethod
    def from_page(cls, page: ChunkPage) -> ChunkPageResponse:
        return cls(
            chunks=tuple(ChunkResponse.from_chunk(chunk) for chunk in page.items),
            total=page.total, next_offset=page.next_offset, edit_revision=page.edit_revision,
            generation_state=page.generation_state,
        )


class ChunkMutationRequest(BaseModel):
    content: str | None = Field(default=None, max_length=1_000_000)
    enabled: bool | None = None
    expected_edit_revision: int = Field(ge=0)

    @model_validator(mode="after")
    def requires_single_mutation(self) -> ChunkMutationRequest:
        if (self.content is None) == (self.enabled is None):
            raise ValueError("provide exactly one of content or enabled")
        return self


class SplitChunkRequest(BaseModel):
    at_character: int = Field(ge=1)
    expected_edit_revision: int = Field(ge=0)


class MergeChunkRequest(BaseModel):
    first_chunk_id: str = Field(min_length=1)
    second_chunk_id: str = Field(min_length=1)
    expected_edit_revision: int = Field(ge=0)


class GenerationMutationResponse(BaseModel):
    id: str
    state: str
    edit_revision: int
    chunks: tuple[ChunkResponse, ...]

    @classmethod
    def from_generation(cls, generation: DraftGeneration) -> GenerationMutationResponse:
        return cls(
            id=generation.id, state=generation.state, edit_revision=generation.edit_revision,
            chunks=tuple(ChunkResponse.from_chunk(chunk) for chunk in generation.chunks),
        )


class GenerationManifestResponse(BaseModel):
    generation_id: str
    document_id: str | None
    build_status: str
    manifest_hash: str
    chunk_count: int
    embedding_profile_id: str
    embedding_dimension: int


class ChunkWorkspaceRegistry:
    """Registry boundary to be replaced by the PostgreSQL repository in deployment."""

    def __init__(self) -> None:
        self._editors: dict[str, DraftEditor] = {}
        self._manifests: dict[str, IndexBuildResult] = {}
        self._vectors = InMemoryVectorStore(dimension=1024)

    def register(self, generation: DraftGeneration) -> None:
        self._editors[generation.id] = DraftEditor(generation)

    def list(self, generation_id: str, **filters: object) -> ChunkPage:
        return self._editor(generation_id).list_chunks(**filters)  # type: ignore[arg-type]

    def get(self, generation_id: str, chunk_id: str) -> DraftChunk:
        return self._editor(generation_id).get_chunk(chunk_id)

    def mutate(self, generation_id: str, chunk_id: str, request: ChunkMutationRequest) -> DraftGeneration:
        editor = self._editor(generation_id)
        if request.content is not None:
            return editor.update_content(chunk_id, request.content, expected_edit_revision=request.expected_edit_revision)
        return editor.set_enabled(chunk_id, request.enabled, expected_edit_revision=request.expected_edit_revision)  # type: ignore[arg-type]

    def split(self, generation_id: str, request: SplitChunkRequest, chunk_id: str) -> DraftGeneration:
        return self._editor(generation_id).split(
            chunk_id, at_character=request.at_character, expected_edit_revision=request.expected_edit_revision
        )

    def merge(self, generation_id: str, request: MergeChunkRequest) -> DraftGeneration:
        return self._editor(generation_id).merge(
            request.first_chunk_id, request.second_chunk_id, expected_edit_revision=request.expected_edit_revision
        )

    def index(self, generation_id: str) -> IndexBuildResult:
        editor = self._editor(generation_id)
        result = build_generation_index(
            editor.generation,
            embedder=DeterministicEmbedder(dimension=1024, profile_id="mock-text-embedding-v4-1024"),
            store=self._vectors,
        )
        editor.mark_build_ready(embedding_profile_id=result.embedding_profile_id, manifest_hash=result.manifest_hash)
        self._manifests[generation_id] = result
        return result

    def manifest(self, generation_id: str) -> IndexBuildResult:
        try:
            return self._manifests[generation_id]
        except KeyError as error:
            raise InvalidChunkOperationError("generation is not build ready") from error

    def _editor(self, generation_id: str) -> DraftEditor:
        try:
            return self._editors[generation_id]
        except KeyError as error:
            raise InvalidChunkOperationError("generation does not exist") from error


class PostgresChunkWorkspaceRegistry:
    """Persistent implementation of the same workspace boundary.

    Editing rules remain in :class:`DraftEditor`; this adapter reloads and saves
    the complete aggregate in one PostgreSQL transaction boundary per command.
    """

    def __init__(self, repository: PostgresRagRepository) -> None:
        self._repository = repository

    def register(self, generation: DraftGeneration) -> None:
        raise InvalidChunkOperationError("persistent generations are created by ingestion")

    def list(self, generation_id: str, **filters: object) -> ChunkPage:
        return DraftEditor(self._load(generation_id)).list_chunks(**filters)  # type: ignore[arg-type]

    def get(self, generation_id: str, chunk_id: str) -> DraftChunk:
        return DraftEditor(self._load(generation_id)).get_chunk(chunk_id)

    def mutate(self, generation_id: str, chunk_id: str, request: ChunkMutationRequest) -> DraftGeneration:
        editor = DraftEditor(self._load(generation_id))
        result = (
            editor.update_content(chunk_id, request.content, expected_edit_revision=request.expected_edit_revision)
            if request.content is not None
            else editor.set_enabled(chunk_id, request.enabled, expected_edit_revision=request.expected_edit_revision)
        )
        self._repository.save_draft(result)
        return result

    def split(self, generation_id: str, request: SplitChunkRequest, chunk_id: str) -> DraftGeneration:
        result = DraftEditor(self._load(generation_id)).split(
            chunk_id, at_character=request.at_character, expected_edit_revision=request.expected_edit_revision
        )
        self._repository.save_draft(result)
        return result

    def merge(self, generation_id: str, request: MergeChunkRequest) -> DraftGeneration:
        result = DraftEditor(self._load(generation_id)).merge(
            request.first_chunk_id, request.second_chunk_id,
            expected_edit_revision=request.expected_edit_revision,
        )
        self._repository.save_draft(result)
        return result

    def index(self, generation_id: str) -> IndexBuildResult:
        return self._repository.build_index(generation_id)

    def manifest(self, generation_id: str) -> IndexBuildResult:
        return self._repository.manifest(generation_id)

    def _load(self, generation_id: str) -> DraftGeneration:
        try:
            return self._repository.load_generation(generation_id)
        except ValueError as error:
            raise InvalidChunkOperationError(str(error)) from error


def mutation_error_status(error: ValueError) -> int:
    if isinstance(error, EditConflictError):
        return 409
    if isinstance(error, DraftReadOnlyError):
        return 409
    return 422

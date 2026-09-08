"""Internal document-preview API used before vector construction."""

from pydantic import BaseModel, Field

from medical_agent.ingestion.chunking import (
    ChunkConfig,
    ConservativeTextTokenCounter,
    DocumentSection,
    chunk_sections,
)


class PreviewSectionRequest(BaseModel):
    title: str = Field(max_length=500)
    text: str = Field(max_length=1_000_000)
    page_number: int | None = Field(default=None, ge=1)


class PreviewChunksRequest(BaseModel):
    sections: tuple[PreviewSectionRequest, ...] = Field(min_length=1, max_length=2_000)
    max_tokens: int = Field(default=600, ge=1, le=4_000)
    overlap_tokens: int = Field(default=100, ge=0)


class PreviewChunkResponse(BaseModel):
    ordinal: int
    title: str
    content: str
    page_number: int | None
    token_count: int
    token_count_source: str


class PreviewChunksResponse(BaseModel):
    tokenizer_version: str
    chunks: tuple[PreviewChunkResponse, ...]


def preview_chunks(payload: PreviewChunksRequest) -> PreviewChunksResponse:
    # The selected Qwen tokenizer is injected by the asynchronous generation
    # worker.  Preview has no provider tokenizer SDK yet, so its count is
    # visibly marked as an estimate instead of pretending to be billable usage.
    token_counter = ConservativeTextTokenCounter()
    config = ChunkConfig(
        max_tokens=payload.max_tokens,
        overlap_tokens=payload.overlap_tokens,
    )
    chunks = chunk_sections(
        tuple(
            DocumentSection(
                title=section.title,
                text=section.text,
                page_number=section.page_number,
            )
            for section in payload.sections
        ),
        config=config,
        token_counter=token_counter,
    )
    return PreviewChunksResponse(
        tokenizer_version=token_counter.version,
        chunks=tuple(
            PreviewChunkResponse(
                ordinal=chunk.ordinal,
                title=chunk.title,
                content=chunk.content,
                page_number=chunk.page_number,
                token_count=chunk.token_count,
                token_count_source=chunk.token_count_source,
            )
            for chunk in chunks
        ),
    )

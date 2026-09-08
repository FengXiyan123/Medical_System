"""Title/paragraph-first chunking for preview before vector construction."""

import re
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol

from medical_agent.ingestion.source import SourceLocation


class TokenCounter(Protocol):
    @property
    def version(self) -> str: ...

    def tokenize(self, text: str) -> tuple[object, ...]: ...

    def detokenize(self, tokens: Sequence[object]) -> str: ...


class ModelTokenizer(Protocol):
    """Adapter for the exact tokenizer selected for a document generation."""

    @property
    def version(self) -> str: ...

    def encode(self, text: str) -> tuple[int, ...]: ...

    def decode(self, tokens: tuple[int, ...]) -> str: ...


@dataclass(frozen=True, slots=True)
class ModelTokenizerCounter:
    """Connects chunking to a model tokenizer without coupling to one SDK."""

    adapter: ModelTokenizer

    @property
    def version(self) -> str:
        return self.adapter.version

    def tokenize(self, text: str) -> tuple[int, ...]:
        return self.adapter.encode(text)

    def detokenize(self, tokens: Sequence[object]) -> str:
        return self.adapter.decode(tuple(int(token) for token in tokens))


@dataclass(frozen=True, slots=True)
class WhitespaceTokenCounter:
    """Deterministic test counter; production must inject the model tokenizer."""

    version: str = "whitespace-v1"
    count_source: str = "test"

    def tokenize(self, text: str) -> tuple[str, ...]:
        return tuple(text.split())

    def detokenize(self, tokens: Sequence[object]) -> str:
        return " ".join(str(token) for token in tokens)


@dataclass(frozen=True, slots=True)
class ConservativeTextTokenCounter:
    """Reversible fallback when the chosen model tokenizer is unavailable.

    It treats every CJK ideograph as a token and preserves whitespace in the
    round trip.  Its explicit ``estimated`` count source stops this value from
    being misrepresented as provider billing usage.
    """

    version: str = "unicode-conservative-v1"
    count_source: str = "estimated"

    def tokenize(self, text: str) -> tuple[str, ...]:
        return tuple(re.findall(r"[\u3400-\u9fff]|[A-Za-z0-9_]+|\s+|[^\w\s]", text))

    def detokenize(self, tokens: Sequence[object]) -> str:
        return "".join(str(token) for token in tokens)


@dataclass(frozen=True, slots=True)
class ChunkConfig:
    max_tokens: int = 600
    overlap_tokens: int = 100

    def __post_init__(self) -> None:
        if self.max_tokens < 1:
            raise ValueError("max_tokens must be positive")
        if not 0 <= self.overlap_tokens < self.max_tokens:
            raise ValueError("overlap_tokens must be less than max_tokens")


@dataclass(frozen=True, slots=True)
class DocumentSection:
    title: str
    text: str
    page_number: int | None = None
    source: SourceLocation | None = None


@dataclass(frozen=True, slots=True)
class DocumentChunk:
    ordinal: int
    title: str
    content: str
    page_number: int | None
    token_count: int
    tokenizer_version: str
    source: SourceLocation | None = None
    token_count_source: str = "tokenizer"


def chunk_sections(
    sections: Sequence[DocumentSection], *, config: ChunkConfig, token_counter: TokenCounter
) -> tuple[DocumentChunk, ...]:
    chunks: list[DocumentChunk] = []
    for section in sections:
        pending_tokens: list[object] = []
        pending_parts: list[str] = []

        for paragraph in _paragraphs(section.text):
            tokens = token_counter.tokenize(paragraph)
            if not tokens:
                continue
            if len(tokens) > config.max_tokens:
                _flush_pending(
                    chunks, section, token_counter, pending_tokens, pending_parts
                )
                for window in _overlapping_windows(tokens, config):
                    chunks.append(
                        DocumentChunk(
                            ordinal=len(chunks),
                            title=section.title,
                            content=token_counter.detokenize(window),
                            page_number=section.page_number,
                            token_count=len(window),
                            tokenizer_version=token_counter.version,
                            source=section.source,
                            token_count_source=getattr(token_counter, "count_source", "tokenizer"),
                        )
                    )
                continue
            if pending_tokens and len(pending_tokens) + len(tokens) > config.max_tokens:
                _flush_pending(
                    chunks, section, token_counter, pending_tokens, pending_parts
                )
            pending_tokens.extend(tokens)
            pending_parts.append(paragraph)
        _flush_pending(chunks, section, token_counter, pending_tokens, pending_parts)
    return tuple(chunks)


def _flush_pending(
    chunks: list[DocumentChunk],
    section: DocumentSection,
    token_counter: TokenCounter,
    pending_tokens: list[object],
    pending_parts: list[str],
) -> None:
    if not pending_tokens:
        return
    chunks.append(
        DocumentChunk(
            ordinal=len(chunks),
            title=section.title,
            content="\n\n".join(pending_parts),
            page_number=section.page_number,
            token_count=len(pending_tokens),
            tokenizer_version=token_counter.version,
            source=section.source,
            token_count_source=getattr(token_counter, "count_source", "tokenizer"),
        )
    )
    pending_tokens.clear()
    pending_parts.clear()


def _paragraphs(text: str) -> tuple[str, ...]:
    return tuple(paragraph.strip() for paragraph in text.split("\n\n") if paragraph.strip())


def _overlapping_windows(
    tokens: Sequence[object], config: ChunkConfig
) -> tuple[tuple[object, ...], ...]:
    step = config.max_tokens - config.overlap_tokens
    windows: list[tuple[object, ...]] = []
    start = 0
    while start < len(tokens):
        window = tuple(tokens[start : start + config.max_tokens])
        windows.append(window)
        if start + config.max_tokens >= len(tokens):
            break
        start += step
    return tuple(windows)

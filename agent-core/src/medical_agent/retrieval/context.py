"""Bounded context construction for the answer model."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ContextCandidate:
    chunk_id: str
    token_count: int


def fit_context(
    candidates: Iterable[ContextCandidate], *, token_limit: int
) -> tuple[str, ...]:
    """Keep ranked candidates that fit without exceeding the exact token budget.

    Oversized high-ranked chunks are skipped so a later concise item can still
    provide evidence.  Text is never silently truncated because its stored
    content hash must keep matching the citation snapshot.
    """

    if token_limit < 1:
        raise ValueError("context token limit must be positive")
    remaining = token_limit
    selected: list[str] = []
    for candidate in candidates:
        if candidate.token_count < 1:
            raise ValueError("context candidate token count must be positive")
        if candidate.token_count <= remaining:
            selected.append(candidate.chunk_id)
            remaining -= candidate.token_count
    return tuple(selected)

"""Citation identifiers and final-answer validation for selected RAG context."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Citation:
    citation_id: str
    chunk_id: str
    generation_id: str
    content_hash: str

    def __post_init__(self) -> None:
        if not all(
            value.strip()
            for value in (self.citation_id, self.chunk_id, self.generation_id, self.content_hash)
        ):
            raise ValueError("citation identity fields must not be blank")


class CitationValidationError(ValueError):
    """An answer tried to cite evidence outside its approved context."""


class CitationValidator:
    def validate(
        self, *, available: tuple[Citation, ...], cited_citation_ids: tuple[str, ...]
    ) -> tuple[Citation, ...]:
        by_id = {citation.citation_id: citation for citation in available}
        invalid = tuple(citation_id for citation_id in cited_citation_ids if citation_id not in by_id)
        if invalid:
            raise CitationValidationError("final citation is not in selected context")
        return tuple(by_id[citation_id] for citation_id in dict.fromkeys(cited_citation_ids))

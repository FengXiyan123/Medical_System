"""Evidence-selection trace used to audit RAG answers."""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RetrievedChunk:
    chunk_id: str
    knowledge_base_id: str
    score: float

    def __post_init__(self) -> None:
        if not self.chunk_id.strip():
            raise ValueError("chunk_id must not be blank")
        if not self.knowledge_base_id.strip():
            raise ValueError("knowledge_base_id must not be blank")
        if not 0 <= self.score <= 1:
            raise ValueError("score must be between 0 and 1")


@dataclass(frozen=True, slots=True)
class SelectedChunk:
    chunk: RetrievedChunk
    selection_reason: str


class KnowledgeTrace:
    """Maintains the retrieved and selected evidence for one run.

    A final answer may cite only selected chunks.  The check belongs here,
    before an answer is persisted or streamed to the caller.
    """

    def __init__(self) -> None:
        self._retrieved: dict[str, RetrievedChunk] = {}
        self._selected: dict[str, SelectedChunk] = {}

    def record_retrieved(self, chunk: RetrievedChunk) -> None:
        existing = self._retrieved.get(chunk.chunk_id)
        if existing is not None and existing != chunk:
            raise ValueError("conflicting retrieved chunk")
        self._retrieved[chunk.chunk_id] = chunk

    def record_selected(self, chunk_id: str, *, reason: str) -> None:
        if not reason.strip():
            raise ValueError("selection reason must not be blank")
        chunk = self._retrieved.get(chunk_id)
        if chunk is None:
            raise ValueError("selected chunk was not retrieved")
        self._selected[chunk_id] = SelectedChunk(chunk=chunk, selection_reason=reason)

    def selected_entries(self) -> tuple[SelectedChunk, ...]:
        return tuple(self._selected.values())

    def citable_chunk_ids(self) -> tuple[str, ...]:
        return tuple(self._selected)

    def validate_final_citations(self, cited_chunk_ids: tuple[str, ...]) -> None:
        selected_ids = set(self._selected)
        invalid_ids = [chunk_id for chunk_id in cited_chunk_ids if chunk_id not in selected_ids]
        if invalid_ids:
            raise ValueError("final citation is not in selected context")

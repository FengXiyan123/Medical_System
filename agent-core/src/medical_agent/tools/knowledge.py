"""Read-only knowledge tools constrained by a gateway-resolved scope."""

from __future__ import annotations

from collections.abc import Mapping

from medical_agent.retrieval.pipeline import RetrievalPipeline
from medical_agent.retrieval.scope import KnowledgeScopeError
from medical_agent.tools.registry import (
    ToolAuthorizationError,
    ToolDefinition,
    ToolExecutionContext,
    ToolRegistry,
)


class KnowledgeTools:
    def __init__(self, pipeline: RetrievalPipeline, *, max_search_results: int = 10, max_read_characters: int = 4000) -> None:
        if max_search_results < 1 or max_read_characters < 1:
            raise ValueError("knowledge tool limits must be positive")
        self._pipeline = pipeline
        self._max_search_results = max_search_results
        self._max_read_characters = max_read_characters

    def register_into(self, registry: ToolRegistry) -> ToolRegistry:
        registry.register(ToolDefinition("knowledge_search", "Search authorized medical knowledge.", _SEARCH_SCHEMA, self.search))
        registry.register(ToolDefinition("knowledge_read", "Read one authorized knowledge chunk.", _READ_SCHEMA, self.read))
        return registry

    def search(self, arguments: Mapping[str, object], context: ToolExecutionContext) -> Mapping[str, object]:
        query = str(arguments["query"])
        requested = tuple(arguments.get("kb_ids", ()))
        top_k = int(arguments.get("top_k", 5))
        try:
            result = self._pipeline.retrieve(
                scope=context.scope,
                query=query,
                requested_knowledge_base_ids=requested,
            )
        except KnowledgeScopeError as error:
            raise ToolAuthorizationError(error.code, str(error)) from error
        hits = result.reranked[: min(top_k, self._max_search_results)]
        return {
            "hits": [
                {
                    "generation_id": chunk.generation_id,
                    "knowledge_base_id": chunk.knowledge_base_id,
                    "chunk_id": chunk.chunk_id,
                    "content_hash": chunk.content_hash,
                    "score": round(1 / (index + 1), 6),
                    "snippet": chunk.content[:500],
                }
                for index, chunk in enumerate(hits)
            ]
        }

    def read(self, arguments: Mapping[str, object], context: ToolExecutionContext) -> Mapping[str, object]:
        try:
            chunk = self._pipeline.read(
                scope=context.scope,
                generation_id=str(arguments["generation_id"]),
                chunk_id=str(arguments["chunk_id"]),
            )
        except KnowledgeScopeError as error:
            raise ToolAuthorizationError(error.code, str(error)) from error
        except ValueError as error:
            raise ToolAuthorizationError("KNOWLEDGE_CHUNK_NOT_FOUND", "knowledge chunk is unavailable") from error
        return {
            "generation_id": chunk.generation_id,
            "knowledge_base_id": chunk.knowledge_base_id,
            "chunk_id": chunk.chunk_id,
            "content_hash": chunk.content_hash,
            "content": chunk.content[: self._max_read_characters],
            "truncated": len(chunk.content) > self._max_read_characters,
        }


_SEARCH_SCHEMA: dict[str, object] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["query"],
    "properties": {
        "query": {"type": "string", "minLength": 1, "maxLength": 1000},
        "kb_ids": {"type": "array", "items": {"type": "string", "minLength": 1, "maxLength": 100}, "minItems": 1, "maxItems": 5, "uniqueItems": True},
        "top_k": {"type": "integer", "minimum": 1, "maximum": 10},
    },
}

_READ_SCHEMA: dict[str, object] = {
    "type": "object",
    "additionalProperties": False,
    "required": ["generation_id", "chunk_id"],
    "properties": {
        "generation_id": {"type": "string", "minLength": 1, "maxLength": 100},
        "chunk_id": {"type": "string", "minLength": 1, "maxLength": 100},
    },
}

"""Authorization-bound retrieval scopes returned by the gateway.

The agent never turns a model-supplied knowledge-base id into a database filter.
It first resolves this immutable scope from the gateway, then every retrieval and
read operation uses only the active generations contained here.
"""

from __future__ import annotations

from dataclasses import dataclass

from medical_agent.contracts.run import RunMode


class KnowledgeScopeError(ValueError):
    """A client-safe scope failure whose code is part of the internal contract."""

    def __init__(self, code: str, message: str) -> None:
        self.code = code
        super().__init__(f"{code}: {message}")


@dataclass(frozen=True, slots=True)
class ScopedGeneration:
    knowledge_base_id: str
    document_id: str
    generation_id: str
    authorization_version: int

    def __post_init__(self) -> None:
        if not all(
            value.strip()
            for value in (self.knowledge_base_id, self.document_id, self.generation_id)
        ):
            raise ValueError("scoped generation identifiers must not be blank")
        if self.authorization_version < 0:
            raise ValueError("authorization_version must not be negative")


@dataclass(frozen=True, slots=True)
class EffectiveRetrievalScope:
    run_id: str
    user_id: str
    mode: RunMode
    generations: tuple[ScopedGeneration, ...]

    @property
    def generation_ids(self) -> frozenset[str]:
        return frozenset(generation.generation_id for generation in self.generations)

    @property
    def knowledge_base_ids(self) -> frozenset[str]:
        return frozenset(generation.knowledge_base_id for generation in self.generations)

    def require_generation(self, generation_id: str) -> ScopedGeneration:
        for generation in self.generations:
            if generation.generation_id == generation_id:
                return generation
        raise KnowledgeScopeError(
            "KNOWLEDGE_SCOPE_FORBIDDEN", "generation is outside the current knowledge scope"
        )


@dataclass(frozen=True, slots=True)
class ResolvedKnowledgeScope:
    """A current scope snapshot for one run.

    ``authorized_knowledge_base_ids`` is intentionally kept independently from
    active generations: an authorized empty knowledge base is not an
    authorization failure, it simply has no retrievable documents yet.
    """

    run_id: str
    user_id: str
    mode: RunMode
    selected_knowledge_base_ids: tuple[str, ...]
    generations: tuple[ScopedGeneration, ...]
    authorized_knowledge_base_ids: tuple[str, ...] | None = None

    def __post_init__(self) -> None:
        if not self.run_id.strip() or not self.user_id.strip():
            raise ValueError("run_id and user_id must not be blank")
        if len(set(self.selected_knowledge_base_ids)) != len(self.selected_knowledge_base_ids):
            raise ValueError("selected knowledge base ids must not contain duplicates")
        if any(not value.strip() for value in self.selected_knowledge_base_ids):
            raise ValueError("selected knowledge base ids must not be blank")
        generation_ids = [generation.generation_id for generation in self.generations]
        if len(set(generation_ids)) != len(generation_ids):
            raise ValueError("scoped generation ids must be unique")

    @classmethod
    def from_gateway_response(
        cls,
        response: dict[str, object],
        *,
        expected_run_id: str,
        expected_user_id: str,
        expected_mode: RunMode,
    ) -> ResolvedKnowledgeScope:
        """Decode the gateway response without accepting a mismatched run ticket.

        HTTP transport belongs to the worker adapter.  Keeping this decoding at
        the security boundary makes every future adapter prove it received a
        scope for the same run, user and mode it is about to execute.
        """

        try:
            run_id = _required_string(response, "run_id")
            user_id = _required_string(response, "user_id")
            mode = RunMode(_required_string(response, "mode"))
            knowledge_base_ids = _string_tuple(response.get("knowledge_base_ids"))
            raw_generations = response.get("generations")
            if not isinstance(raw_generations, list):
                raise TypeError("generations must be a list")
            generations = tuple(
                ScopedGeneration(
                    knowledge_base_id=_required_string(value, "knowledge_base_id"),
                    document_id=_required_string(value, "document_id"),
                    generation_id=_required_string(value, "generation_id"),
                    authorization_version=_required_nonnegative_int(value, "authorization_version"),
                )
                for value in raw_generations
                if isinstance(value, dict)
            )
            if len(generations) != len(raw_generations):
                raise ValueError("generation must be an object")
        except (TypeError, ValueError) as error:
            raise KnowledgeScopeError("KNOWLEDGE_SCOPE_FORBIDDEN", "invalid gateway scope response") from error
        if (run_id, user_id, mode) != (expected_run_id, expected_user_id, expected_mode):
            raise KnowledgeScopeError("KNOWLEDGE_SCOPE_FORBIDDEN", "gateway scope is not bound to this run")
        return cls(
            run_id=run_id,
            user_id=user_id,
            mode=mode,
            selected_knowledge_base_ids=knowledge_base_ids if mode is RunMode.MANUAL_KB else (),
            generations=generations,
            authorized_knowledge_base_ids=knowledge_base_ids,
        )

    @property
    def authorized_ids(self) -> frozenset[str]:
        if self.authorized_knowledge_base_ids is None:
            return frozenset(generation.knowledge_base_id for generation in self.generations)
        return frozenset(self.authorized_knowledge_base_ids)

    def require_retrieval_scope(
        self, *, requested_knowledge_base_ids: tuple[str, ...] = ()
    ) -> EffectiveRetrievalScope:
        requested = _unique_nonblank(requested_knowledge_base_ids)
        if self.mode is RunMode.MANUAL_KB:
            if not self.selected_knowledge_base_ids:
                raise KnowledgeScopeError(
                    "KNOWLEDGE_SELECTION_REQUIRED", "manual knowledge mode requires a selection"
                )
            selected = frozenset(self.selected_knowledge_base_ids)
            if requested and frozenset(requested) != selected:
                raise KnowledgeScopeError(
                    "KNOWLEDGE_SCOPE_FORBIDDEN", "manual knowledge mode cannot change its selected scope"
                )
            target_ids = selected
        else:
            target_ids = frozenset(requested) if requested else self.authorized_ids

        unauthorized = target_ids - self.authorized_ids
        if unauthorized:
            raise KnowledgeScopeError(
                "KNOWLEDGE_SCOPE_FORBIDDEN", "knowledge base is not currently authorized"
            )
        return EffectiveRetrievalScope(
            run_id=self.run_id,
            user_id=self.user_id,
            mode=self.mode,
            generations=tuple(
                generation
                for generation in self.generations
                if generation.knowledge_base_id in target_ids
            ),
        )


def _unique_nonblank(values: tuple[str, ...]) -> tuple[str, ...]:
    if any(not value.strip() for value in values):
        raise KnowledgeScopeError("KNOWLEDGE_SCOPE_FORBIDDEN", "knowledge base id must not be blank")
    if len(set(values)) != len(values):
        raise KnowledgeScopeError("KNOWLEDGE_SCOPE_FORBIDDEN", "knowledge base ids must be unique")
    return values


def _required_string(value: object, name: str) -> str:
    if not isinstance(value, dict):
        raise TypeError("scope item must be an object")
    result = value.get(name)
    if not isinstance(result, str) or not result.strip():
        raise ValueError(f"{name} must be a nonblank string")
    return result


def _required_nonnegative_int(value: object, name: str) -> int:
    if not isinstance(value, dict):
        raise TypeError("scope item must be an object")
    result = value.get(name)
    if not isinstance(result, int) or result < 0:
        raise ValueError(f"{name} must be a nonnegative integer")
    return result


def _string_tuple(value: object) -> tuple[str, ...]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise ValueError("knowledge_base_ids must be a list of ids")
    if len(set(value)) != len(value):
        raise ValueError("knowledge_base_ids must not contain duplicates")
    return tuple(value)

"""Mode-aware resource routing before the LangGraph execution begins.

The automatic knowledge router is deliberately a narrow structured-output
boundary.  A model may rank *only* the candidates supplied by the gateway
scope; it never gets to invent a knowledge-base id that later becomes a
retrieval filter.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass, replace
from time import monotonic
from typing import Protocol

from medical_agent.contracts.run import CreateRunRequest, RunMode
from medical_agent.retrieval.scope import ResolvedKnowledgeScope


@dataclass(frozen=True, slots=True)
class ExecutionRoute:
    route_name: str
    knowledge_base_ids: tuple[str, ...]
    tool_names: tuple[str, ...]
    agent_may_choose_resources: bool


class StructuredKnowledgeSelector(Protocol):
    """Model adapter for JSON-schema / structured-output knowledge selection."""

    def select(self, request: Mapping[str, object]) -> Mapping[str, object]: ...


class AutoKnowledgeRoutingError(ValueError):
    """The model failed to return a safe routing decision after one repair."""


@dataclass(frozen=True, slots=True)
class StructuredKnowledgeSelection:
    """Strictly decoded, model-produced selection before scope validation."""

    knowledge_base_ids: tuple[str, ...]
    reasons: dict[str, str]
    input_tokens: int | None = None
    output_tokens: int | None = None

    @classmethod
    def from_response(cls, response: Mapping[str, object]) -> StructuredKnowledgeSelection:
        raw_ids = response.get("knowledge_base_ids")
        raw_reasons = response.get("reasons")
        if not isinstance(raw_ids, list) or any(
            not isinstance(value, str) or not value.strip() for value in raw_ids
        ):
            raise ValueError("knowledge_base_ids must be a list of nonblank ids")
        if len(raw_ids) > 3:
            raise ValueError("knowledge_base_ids may contain at most three ids")
        if len(set(raw_ids)) != len(raw_ids):
            raise ValueError("knowledge_base_ids must not contain duplicates")
        if not isinstance(raw_reasons, Mapping):
            raise TypeError("reasons must be an object")
        reasons: dict[str, str] = {}
        for knowledge_base_id in raw_ids:
            reason = raw_reasons.get(knowledge_base_id)
            if not isinstance(reason, str) or not reason.strip():
                raise ValueError("selection reason is required for every knowledge base")
            reasons[knowledge_base_id] = reason.strip()
        extra_reasons = set(raw_reasons) - set(raw_ids)
        if extra_reasons:
            raise ValueError("reasons may only describe selected knowledge bases")
        input_tokens, output_tokens = _usage_from_response(response)
        return cls(tuple(raw_ids), reasons, input_tokens, output_tokens)


@dataclass(frozen=True, slots=True)
class AutoKnowledgeRouteTrace:
    """Persistable audit record for an automatic knowledge-routing decision."""

    candidate_knowledge_base_ids: tuple[str, ...]
    selected_knowledge_base_ids: tuple[str, ...]
    selection_reasons: dict[str, str]
    router_version: str
    duration_ms: int
    input_tokens: int | None
    output_tokens: int | None
    unknown_usage_attempt_count: int
    repair_attempted: bool
    used_authorized_scope_fallback: bool


@dataclass(frozen=True, slots=True)
class AutoKnowledgeRoute:
    """A scope-safe selection plus an optional direct greeting response."""

    requested_knowledge_base_ids: tuple[str, ...]
    trace: AutoKnowledgeRouteTrace
    direct_answer: str | None = None

    def selected_event_payload(self) -> dict[str, object]:
        """Stable event shape for gateway persistence and the Vue route summary."""

        return {
            "candidates": [
                {"knowledge_base_id": knowledge_base_id}
                for knowledge_base_id in self.trace.candidate_knowledge_base_ids
            ],
            "selected_knowledge_base_ids": list(self.trace.selected_knowledge_base_ids),
            "selection_reason": dict(self.trace.selection_reasons),
            "fallback_used": self.trace.used_authorized_scope_fallback,
            "router_version": self.trace.router_version,
            "duration_ms": self.trace.duration_ms,
            "input_tokens": self.trace.input_tokens,
            "output_tokens": self.trace.output_tokens,
            "unknown_usage_attempt_count": self.trace.unknown_usage_attempt_count,
            "usage_status": "UNKNOWN"
            if self.trace.unknown_usage_attempt_count
            else "PROVIDER_REPORTED",
            "repair_attempted": self.trace.repair_attempted,
        }


class AutoKnowledgeRouter:
    """Chooses up to three active authorized knowledge bases with one repair.

    The returned ``requested_knowledge_base_ids`` is suitable for
    ``ResolvedKnowledgeScope.require_retrieval_scope``.  An empty tuple means
    "use the current authorized scope"; it is intentionally used when the
    router has no confident match so medical questions can still reach the
    normal evidence gate.
    """

    ROUTER_VERSION = "auto-kb-router-v1"
    _FALLBACK_REASON = "路由未命中，使用全部已授权且有活动资料的知识库"
    _GREETING_ANSWER = "你好，我可以基于已授权的医疗知识库回答健康教育问题。"

    def __init__(
        self,
        selector: StructuredKnowledgeSelector,
        *,
        clock: Callable[[], float] = monotonic,
    ) -> None:
        self._selector = selector
        self._clock = clock

    def route(self, *, question: str, scope: ResolvedKnowledgeScope) -> AutoKnowledgeRoute:
        if scope.mode is not RunMode.AUTO_KB:
            raise ValueError("automatic knowledge routing requires AUTO_KB mode")
        if not question.strip():
            raise ValueError("question must not be blank")
        if _is_greeting(question):
            return AutoKnowledgeRoute(
                requested_knowledge_base_ids=(),
                direct_answer=self._GREETING_ANSWER,
                trace=AutoKnowledgeRouteTrace(
                    candidate_knowledge_base_ids=(),
                    selected_knowledge_base_ids=(),
                    selection_reasons={},
                    router_version=self.ROUTER_VERSION,
                    duration_ms=0,
                    input_tokens=0,
                    output_tokens=0,
                    unknown_usage_attempt_count=0,
                    repair_attempted=False,
                    used_authorized_scope_fallback=False,
                ),
            )

        candidates = _active_authorized_candidates(scope)
        candidate_ids = tuple(candidate["knowledge_base_id"] for candidate in candidates)
        if not candidates:
            return self._fallback(
                candidate_ids,
                duration_ms=0,
                input_tokens=0,
                output_tokens=0,
                unknown_usage_attempt_count=0,
            )

        started_at = self._clock()
        total_input_tokens = 0
        total_output_tokens = 0
        unknown_usage_attempt_count = 0
        repair_attempted = False
        request = self._request(question, candidates)
        for attempt_no in (1, 2):
            response = self._selector.select(request)
            try:
                input_tokens, output_tokens = _usage_from_response(response)
                if input_tokens is None or output_tokens is None:
                    unknown_usage_attempt_count += 1
                total_input_tokens += input_tokens or 0
                total_output_tokens += output_tokens or 0
                selection = StructuredKnowledgeSelection.from_response(response)
                self._validate_authorized_selection(selection, candidate_ids)
            except (TypeError, ValueError) as error:
                if attempt_no == 2:
                    raise AutoKnowledgeRoutingError("invalid knowledge base selection") from error
                repair_attempted = True
                request = self._request(question, candidates, invalid_selection=str(error))
                continue
            duration_ms = max(0, int((self._clock() - started_at) * 1000))
            if not selection.knowledge_base_ids:
                fallback = self._fallback(
                    candidate_ids,
                    duration_ms=duration_ms,
                    input_tokens=_reported_total(total_input_tokens, unknown_usage_attempt_count),
                    output_tokens=_reported_total(total_output_tokens, unknown_usage_attempt_count),
                    unknown_usage_attempt_count=unknown_usage_attempt_count,
                )
                return AutoKnowledgeRoute(
                    requested_knowledge_base_ids=fallback.requested_knowledge_base_ids,
                    direct_answer=fallback.direct_answer,
                    trace=replace(fallback.trace, repair_attempted=repair_attempted),
                )
            return AutoKnowledgeRoute(
                requested_knowledge_base_ids=selection.knowledge_base_ids,
                trace=AutoKnowledgeRouteTrace(
                    candidate_knowledge_base_ids=candidate_ids,
                    selected_knowledge_base_ids=selection.knowledge_base_ids,
                    selection_reasons=selection.reasons,
                    router_version=self.ROUTER_VERSION,
                    duration_ms=duration_ms,
                    input_tokens=_reported_total(total_input_tokens, unknown_usage_attempt_count),
                    output_tokens=_reported_total(total_output_tokens, unknown_usage_attempt_count),
                    unknown_usage_attempt_count=unknown_usage_attempt_count,
                    repair_attempted=repair_attempted,
                    used_authorized_scope_fallback=False,
                ),
            )
        raise AssertionError("selection loop must return or raise")

    def _fallback(
        self,
        candidate_ids: tuple[str, ...],
        *,
        duration_ms: int,
        input_tokens: int | None,
        output_tokens: int | None,
        unknown_usage_attempt_count: int,
    ) -> AutoKnowledgeRoute:
        return AutoKnowledgeRoute(
            requested_knowledge_base_ids=(),
            trace=AutoKnowledgeRouteTrace(
                candidate_knowledge_base_ids=candidate_ids,
                selected_knowledge_base_ids=candidate_ids,
                selection_reasons={knowledge_base_id: self._FALLBACK_REASON for knowledge_base_id in candidate_ids},
                router_version=self.ROUTER_VERSION,
                duration_ms=duration_ms,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                unknown_usage_attempt_count=unknown_usage_attempt_count,
                repair_attempted=False,
                used_authorized_scope_fallback=True,
            ),
        )

    @staticmethod
    def _request(
        question: str,
        candidates: tuple[dict[str, object], ...],
        *,
        invalid_selection: str | None = None,
    ) -> dict[str, object]:
        request: dict[str, object] = {
            "question": question,
            "candidates": list(candidates),
            "instruction": "仅从 candidates 的 knowledge_base_id 中选择至多三个；每个选择都必须说明理由。无匹配时返回空数组。",
            "response_schema": {
                "knowledge_base_ids": "string[] (maximum 3)",
                "reasons": "object keyed by selected knowledge_base_id",
            },
        }
        if invalid_selection is not None:
            request["invalid_selection"] = invalid_selection
        return request

    @staticmethod
    def _validate_authorized_selection(
        selection: StructuredKnowledgeSelection, candidate_ids: tuple[str, ...]
    ) -> None:
        invalid_ids = set(selection.knowledge_base_ids) - set(candidate_ids)
        if invalid_ids:
            raise ValueError("selected id is not authorized and active")


def _active_authorized_candidates(scope: ResolvedKnowledgeScope) -> tuple[dict[str, object], ...]:
    authorized = scope.authorized_ids
    active_counts: dict[str, int] = {}
    for generation in scope.generations:
        if generation.knowledge_base_id in authorized:
            active_counts[generation.knowledge_base_id] = (
                active_counts.get(generation.knowledge_base_id, 0) + 1
            )
    preferred_order = scope.authorized_knowledge_base_ids or tuple(active_counts)
    return tuple(
        {
            "knowledge_base_id": knowledge_base_id,
            "active_generation_count": active_counts[knowledge_base_id],
        }
        for knowledge_base_id in preferred_order
        if knowledge_base_id in active_counts
    )


def _usage_from_response(response: Mapping[str, object]) -> tuple[int | None, int | None]:
    raw_usage = response.get("usage")
    if raw_usage is None:
        return None, None
    if not isinstance(raw_usage, Mapping):
        raise TypeError("usage must be an object")
    input_tokens = raw_usage.get("input_tokens")
    output_tokens = raw_usage.get("output_tokens")
    if not isinstance(input_tokens, int) or input_tokens < 0:
        raise ValueError("usage input_tokens must be a non-negative integer")
    if not isinstance(output_tokens, int) or output_tokens < 0:
        raise ValueError("usage output_tokens must be a non-negative integer")
    return input_tokens, output_tokens


def _reported_total(total: int, unknown_usage_attempt_count: int) -> int | None:
    """Avoid claiming a billable total when any model call did not report usage."""

    return total if unknown_usage_attempt_count == 0 else None


def _is_greeting(question: str) -> bool:
    normalized = question.strip().casefold().strip("!！。,.，？? ")
    return normalized in {"你好", "您好", "嗨", "哈喽", "在吗", "早上好", "下午好", "晚上好", "hi", "hello"}


class ExecutionRouter:
    """Creates an auditable, least-privilege execution route for each run."""

    def plan(
        self,
        request: CreateRunRequest,
        *,
        authorized_knowledge_base_ids: tuple[str, ...],
        authorized_tool_names: tuple[str, ...],
    ) -> ExecutionRoute:
        knowledge_base_ids = self._unique(authorized_knowledge_base_ids)
        tool_names = self._unique(authorized_tool_names)

        if request.mode is RunMode.MANUAL_KB:
            selected_ids = request.knowledge_base_ids
            unauthorized = set(selected_ids) - set(knowledge_base_ids)
            if unauthorized:
                raise ValueError("selected knowledge base is not authorized")
            return ExecutionRoute(
                route_name="MANUAL_KB_RETRIEVAL",
                knowledge_base_ids=selected_ids,
                tool_names=(),
                agent_may_choose_resources=False,
            )

        if request.mode is RunMode.AUTO_KB:
            return ExecutionRoute(
                route_name="AUTO_KB_RETRIEVAL",
                knowledge_base_ids=knowledge_base_ids,
                tool_names=(),
                agent_may_choose_resources=False,
            )

        return ExecutionRoute(
            route_name="AGENT_AUTONOMOUS",
            knowledge_base_ids=knowledge_base_ids,
            tool_names=tool_names,
            agent_may_choose_resources=True,
        )

    @staticmethod
    def _unique(values: tuple[str, ...]) -> tuple[str, ...]:
        return tuple(dict.fromkeys(values))

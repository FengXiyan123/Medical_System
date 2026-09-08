"""Small, provider-neutral trace spans for Agent Core execution.

Spans deliberately record timing and structured identifiers only.  Prompt text,
documents and credentials do not belong in this in-memory audit primitive.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from uuid import uuid4


class SpanStatus(StrEnum):
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


@dataclass(slots=True)
class Span:
    span_id: str
    trace_id: str
    parent_span_id: str | None
    kind: str
    node_name: str
    started_at: datetime
    status: SpanStatus = SpanStatus.RUNNING
    ended_at: datetime | None = None
    error_code: str | None = None


class SpanRecorder:
    """Owns spans for one or more local runs without deriving parent usage.

    Model usage is accounted for only in :class:`UsageLedger`; spans are a
    topology/timing view and must never be summed for token billing.
    """

    def __init__(self) -> None:
        self._spans: dict[str, Span] = {}
        self._order: list[str] = []

    def start(
        self,
        *,
        trace_id: str,
        node_name: str,
        kind: str,
        parent_span_id: str | None = None,
    ) -> Span:
        if not trace_id.strip():
            raise ValueError("trace_id must not be blank")
        if not node_name.strip():
            raise ValueError("node_name must not be blank")
        if parent_span_id is not None and parent_span_id not in self._spans:
            raise ValueError("parent span does not exist")
        span = Span(
            span_id=uuid4().hex,
            trace_id=trace_id,
            parent_span_id=parent_span_id,
            kind=kind,
            node_name=node_name,
            started_at=datetime.now(UTC),
        )
        self._spans[span.span_id] = span
        self._order.append(span.span_id)
        return span

    def finish(
        self,
        span_id: str,
        *,
        status: SpanStatus = SpanStatus.SUCCEEDED,
        error_code: str | None = None,
    ) -> Span:
        span = self._spans.get(span_id)
        if span is None:
            raise ValueError("span does not exist")
        if span.status is not SpanStatus.RUNNING:
            raise ValueError("span is already finished")
        if status is SpanStatus.RUNNING:
            raise ValueError("finished span must be terminal")
        span.status = status
        span.error_code = error_code
        span.ended_at = datetime.now(UTC)
        return span

    def finished(self) -> tuple[Span, ...]:
        return tuple(
            self._spans[span_id]
            for span_id in self._order
            if self._spans[span_id].status is not SpanStatus.RUNNING
        )

    def trace_id_for(self, span_id: str) -> str:
        span = self._spans.get(span_id)
        if span is None:
            raise ValueError("span does not exist")
        return span.trace_id

"""Instrumented streaming model calls shared by RAG and future Agent graphs."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from typing import Protocol
from uuid import uuid4

from medical_agent.graph.budget import InvocationPurpose, RunBudget
from medical_agent.models.qwen_stream import ChatStreamResult, consume_chat_stream
from medical_agent.tracing.spans import SpanRecorder, SpanStatus
from medical_agent.tracing.usage import InvocationUsage, UsageLedger


class StreamingChatModel(Protocol):
    def stream(self, request: Mapping[str, object]) -> Iterable[Mapping[str, object]]: ...


class InvocationBudgetExceeded(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class ModelInvocation:
    invocation_id: str
    attempt_no: int
    purpose: InvocationPurpose
    provider: str
    model: str
    span_id: str
    status: SpanStatus
    usage: InvocationUsage


@dataclass(frozen=True, slots=True)
class InstrumentedChatResult:
    content: str
    finish_reason: str | None
    invocation_id: str
    attempt_no: int


@dataclass(slots=True)
class InstrumentedChatModel:
    """Records one usage row and one leaf span for every actual provider call."""

    client: StreamingChatModel
    provider: str
    model: str
    usage_ledger: UsageLedger
    spans: SpanRecorder
    invocation_id_factory: Callable[[], str] = field(default=lambda: uuid4().hex)
    invocations: list[ModelInvocation] = field(default_factory=list)

    def __post_init__(self) -> None:
        if not self.provider.strip() or not self.model.strip():
            raise ValueError("provider and model must not be blank")

    def invoke(
        self,
        *,
        purpose: InvocationPurpose,
        request: Mapping[str, object],
        parent_span_id: str,
        budget: RunBudget,
        planned_chat_tokens: int,
        attempt_no: int = 1,
        invocation_id: str | None = None,
        on_delta: Callable[[str], None] | None = None,
    ) -> InstrumentedChatResult:
        if not budget.try_reserve(purpose, planned_chat_tokens):
            raise InvocationBudgetExceeded("model invocation budget exhausted")
        actual_invocation_id = invocation_id or self.invocation_id_factory()
        model_span = self.spans.start(
            trace_id=self._trace_id(parent_span_id),
            parent_span_id=parent_span_id,
            node_name=purpose.value.lower(),
            kind="MODEL_INVOCATION",
        )
        try:
            streamed = consume_chat_stream(self.client.stream(request), on_delta=on_delta)
        except Exception:
            usage = InvocationUsage.unknown(
                invocation_id=actual_invocation_id, attempt_no=attempt_no
            )
            self.usage_ledger.record(usage)
            self.spans.finish(model_span.span_id, status=SpanStatus.FAILED, error_code="MODEL_CALL_FAILED")
            self.invocations.append(
                ModelInvocation(
                    invocation_id=actual_invocation_id,
                    attempt_no=attempt_no,
                    purpose=purpose,
                    provider=self.provider,
                    model=self.model,
                    span_id=model_span.span_id,
                    status=SpanStatus.FAILED,
                    usage=usage,
                )
            )
            raise

        usage = self._usage_for(streamed, actual_invocation_id, attempt_no)
        self.usage_ledger.record(usage)
        self.spans.finish(model_span.span_id)
        self.invocations.append(
            ModelInvocation(
                invocation_id=actual_invocation_id,
                attempt_no=attempt_no,
                purpose=purpose,
                provider=self.provider,
                model=self.model,
                span_id=model_span.span_id,
                status=SpanStatus.SUCCEEDED,
                usage=usage,
            )
        )
        return InstrumentedChatResult(
            content=streamed.content,
            finish_reason=streamed.finish_reason,
            invocation_id=actual_invocation_id,
            attempt_no=attempt_no,
        )

    def _trace_id(self, parent_span_id: str) -> str:
        for span in self.spans.finished():
            if span.span_id == parent_span_id:
                return span.trace_id
        # Parent node may still be running and therefore does not appear in finished().
        return self.spans.trace_id_for(parent_span_id)

    @staticmethod
    def _usage_for(
        streamed: ChatStreamResult, invocation_id: str, attempt_no: int
    ) -> InvocationUsage:
        if streamed.usage is None:
            return InvocationUsage.unknown(invocation_id=invocation_id, attempt_no=attempt_no)
        return InvocationUsage.reported(
            invocation_id=invocation_id,
            attempt_no=attempt_no,
            input_tokens=streamed.usage["input_tokens"],
            output_tokens=streamed.usage["output_tokens"],
        )

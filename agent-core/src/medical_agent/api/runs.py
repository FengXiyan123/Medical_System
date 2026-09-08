"""Internal run admission called by the Java gateway."""

import base64
import hashlib
import hmac
import json
import logging
import threading
import time
import urllib.request
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime

from pydantic import BaseModel, Field

from medical_agent.config import ModelMode, Settings
from medical_agent.contracts.run import CreateRunRequest
from medical_agent.graph.budget import InvocationPurpose, RunBudget
from medical_agent.graph.routing import ExecutionRoute, ExecutionRouter
from medical_agent.ingestion.indexing import DeterministicEmbedder
from medical_agent.models.dashscope import DashScopeStreamingChatModel
from medical_agent.models.instrumented import InstrumentedChatModel
from medical_agent.models.mock import DeterministicMockChatModel
from medical_agent.models.qwen_config import DashScopeChatProfile
from medical_agent.persistence.pg_rag import (
    PostgresChunkCatalog,
    PostgresQueryableVectorStore,
)
from medical_agent.persistence.rag_factory import create_rag_repository
from medical_agent.persistence.runs import InMemoryRunRepository, RunRecord, RunStatus
from medical_agent.retrieval.pipeline import RetrievalPipeline
from medical_agent.retrieval.scope import ResolvedKnowledgeScope
from medical_agent.tracing.spans import SpanRecorder
from medical_agent.tracing.usage import UsageLedger

logger = logging.getLogger(__name__)


class PrepareRunRequest(BaseModel):
    run_id: str = Field(min_length=1, max_length=64)
    user_id: str = Field(min_length=1, max_length=64)
    request: CreateRunRequest
    authorized_knowledge_base_ids: tuple[str, ...] = ()
    authorized_tool_names: tuple[str, ...] = ()


class PreparedRunResponse(BaseModel):
    run_id: str
    user_id: str
    route_name: str
    knowledge_base_ids: tuple[str, ...]
    tool_names: tuple[str, ...]
    agent_may_choose_resources: bool

    @classmethod
    def from_route(cls, request: PrepareRunRequest, route: ExecutionRoute) -> "PreparedRunResponse":
        return cls(
            run_id=request.run_id,
            user_id=request.user_id,
            route_name=route.route_name,
            knowledge_base_ids=route.knowledge_base_ids,
            tool_names=route.tool_names,
            agent_may_choose_resources=route.agent_may_choose_resources,
        )


def prepare_run(payload: PrepareRunRequest) -> PreparedRunResponse:
    route = ExecutionRouter().plan(
        payload.request,
        authorized_knowledge_base_ids=payload.authorized_knowledge_base_ids,
        authorized_tool_names=payload.authorized_tool_names,
    )
    return PreparedRunResponse.from_route(payload, route)


class SubmittedRunResponse(PreparedRunResponse):
    status: RunStatus
    created: bool

    @classmethod
    def from_record(
        cls, request: PrepareRunRequest, route: ExecutionRoute, record: RunRecord, *, created: bool
    ) -> "SubmittedRunResponse":
        return cls(
            **PreparedRunResponse.from_route(request, route).model_dump(),
            status=record.status,
            created=created,
        )


@dataclass(frozen=True, slots=True)
class GeneratedAnswer:
    text: str
    input_tokens: int | None
    output_tokens: int | None
    usage_status: str


class InternalRunCoordinator:
    """Admits a delivery once before a bounded worker claims its lease."""

    def __init__(self, repository: InMemoryRunRepository | None = None) -> None:
        self.repository = repository or InMemoryRunRepository()

    def submit(self, payload: PrepareRunRequest) -> SubmittedRunResponse:
        route = ExecutionRouter().plan(
            payload.request,
            authorized_knowledge_base_ids=payload.authorized_knowledge_base_ids,
            authorized_tool_names=payload.authorized_tool_names,
        )
        record, created = self.repository.enqueue(
            run_id=payload.run_id,
            payload_fingerprint=_payload_fingerprint(payload),
        )
        return SubmittedRunResponse.from_record(payload, route, record, created=created)

    def execute_async(self, payload: PrepareRunRequest, *, gateway_base_url: str, service_secret: str) -> SubmittedRunResponse:
        response = self.submit(payload)
        if not response.created:
            return response
        threading.Thread(target=self._execute, args=(payload, response, gateway_base_url, service_secret), daemon=True).start()
        return response

    def _execute(self, payload: PrepareRunRequest, response: SubmittedRunResponse, gateway: str, secret: str) -> None:
        worker_id = f"run-{payload.run_id}"
        if self.repository.claim(run_id=payload.run_id, worker_id=worker_id, now=datetime.now(UTC)) is None:
            return
        sequence = 1
        def event(kind: str, body: dict[str, object]) -> None:
            nonlocal sequence
            token = _callback_token(secret, payload.run_id)
            request = urllib.request.Request(f"{gateway}/api/internal/v1/runs/{payload.run_id}/events", data=json.dumps({"eventId":str(uuid.uuid4()),"sequence":sequence,"type":kind,"payload":body}).encode(), headers={"Content-Type":"application/json","Authorization":f"Bearer {token}"}, method="POST")
            with urllib.request.urlopen(request, timeout=10): pass
            sequence += 1
        try:
            event("run.started", {"route": response.route_name})
            event("route.selected", {"routeName": response.route_name, "knowledgeBaseIds": list(response.knowledge_base_ids)})
            retrieval = _retrieve(payload, gateway=gateway, secret=secret)
            event("retrieval.completed", {"evidence": [
                {"stage": item.stage.value, "rank": item.rank, "knowledgeBaseId": item.knowledge_base_id,
                 "generationId": item.generation_id, "chunkId": item.chunk_id, "score": item.raw_score,
                 "text": item.text_snapshot}
                for item in retrieval.evidence
            ]})
            generated = _generate_answer(
                payload.request.question,
                context=tuple(item.content for item in retrieval.context),
                on_delta=lambda text: event("answer.delta", {"text": text}),
            )
            answer = generated.text
            event("usage.updated", {"inputTokens": generated.input_tokens, "outputTokens": generated.output_tokens, "status": generated.usage_status})
            citations = [{"citationId": item.citation_id, "knowledgeBaseId": item.knowledge_base_id,
                          "generationId": item.generation_id, "chunkId": item.chunk_id,
                          "contentHash": item.content_hash, "text": item.content}
                         for item in retrieval.context]
            event("run.completed", {"answer": answer, "citations": citations})
            self.repository.complete(payload.run_id, worker_id=worker_id, now=datetime.now(UTC))
        except Exception:  # noqa: BLE001 - the client receives a stable terminal event for any worker failure.
            logger.exception("agent run %s failed after route %s", payload.run_id, response.route_name)
            self.repository.fail(payload.run_id, worker_id=worker_id, reason="AGENT_EXECUTION_FAILED", now=datetime.now(UTC))
            try:
                event("run.failed", {"reason": "AGENT_EXECUTION_FAILED"})
            except Exception:  # noqa: BLE001 - the original execution failure is already retained locally.
                logger.warning("Unable to send failed-run callback for run %s", payload.run_id)


def _retrieve(payload: PrepareRunRequest, *, gateway: str, secret: str):
    """Resolve the gateway-owned scope immediately before querying pgvector."""
    from medical_agent.retrieval.pipeline import RetrievalResult

    settings = Settings()
    dsn = settings.effective_postgres_dsn
    if not dsn:
        return RetrievalResult((), (), (), ())
    body = {"run_id": payload.run_id, "user_id": payload.user_id, "mode": payload.request.mode.value,
            "requested_knowledge_base_ids": list(payload.request.knowledge_base_ids)}
    request = urllib.request.Request(
        f"{gateway}/api/internal/v1/knowledge/scopes:resolve", data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {_service_token(secret, 'knowledge:scope', payload.run_id)}"},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        scope_payload = json.loads(response.read())
    scope = ResolvedKnowledgeScope.from_gateway_response(
        scope_payload, expected_run_id=payload.run_id, expected_user_id=payload.user_id,
        expected_mode=payload.request.mode,
    )
    repository = create_rag_repository(settings)
    pipeline = RetrievalPipeline(
        PostgresQueryableVectorStore(repository), PostgresChunkCatalog(repository),
        DeterministicEmbedder(1024, "mock-text-embedding-v4-1024"),
        profile_id="mock-text-embedding-v4-1024",
    )
    return pipeline.retrieve(scope=scope, query=payload.request.question,
                             requested_knowledge_base_ids=payload.request.knowledge_base_ids)


def _generate_answer(
    question: str,
    settings: Settings | None = None,
    *,
    context: tuple[str, ...] = (),
    on_delta: Callable[[str], None] | None = None,
) -> GeneratedAnswer:
    settings = settings or Settings()
    response_text = (
        "这是医疗智能问答系统的演示回答：关于“"
        f"{question}”，请结合医生的专业意见进行判断。"
    )
    if settings.model_mode is ModelMode.MOCK:
        client = DeterministicMockChatModel(
            response_text=response_text,
            input_tokens=len(question),
            output_tokens=len(response_text),
        )
        provider, model = "mock", "deterministic-demo"
    else:
        client = DashScopeStreamingChatModel(
            DashScopeChatProfile(
                workspace_id=settings.dashscope_workspace_id or "",
                region=settings.dashscope_region,
                api_key=settings.dashscope_api_key or "",
                base_url=settings.dashscope_base_url,
            )
        )
        provider, model = "dashscope", "qwen3.6-flash"
    usage = UsageLedger()
    spans = SpanRecorder()
    run_span = spans.start(trace_id="model-call", node_name="answer", kind="RUN")
    knowledge_context = "\n\n".join(context)
    result = InstrumentedChatModel(client=client, provider=provider, model=model, usage_ledger=usage, spans=spans).invoke(
        purpose=InvocationPurpose.ANSWER,
        request={
            "messages": [
                {"role": "system", "content": "你是医疗健康知识问答助手。仅提供教学性健康知识，不做诊断或处方；建议必要时咨询专业医生。"},
                {"role": "user", "content": question + (f"\n\n可引用知识：\n{knowledge_context}" if knowledge_context else "")},
            ]
        },
        parent_span_id=run_span.span_id,
        budget=RunBudget(max_invocations=1, max_chat_tokens=2048, final_answer_reserve_tokens=512),
        planned_chat_tokens=512,
        on_delta=on_delta,
    )
    spans.finish(run_span.span_id)
    summary = usage.summary()
    return GeneratedAnswer(
        text=result.content.strip() or "暂未生成可展示的回答，请重试。",
        input_tokens=summary.input_tokens if summary.reported_invocation_count else None,
        output_tokens=summary.output_tokens if summary.reported_invocation_count else None,
        usage_status="PROVIDER_REPORTED" if summary.reported_invocation_count else "UNKNOWN",
    )


def _payload_fingerprint(payload: PrepareRunRequest) -> str:
    canonical = json.dumps(
        payload.model_dump(mode="json"), ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    return hashlib.sha256(canonical.encode()).hexdigest()

def _callback_token(secret: str, run_id: str) -> str:
    return _service_token(secret, "runs:callback", run_id)


def _service_token(secret: str, scope: str, run_id: str | None = None) -> str:
    header = _b64({"alg":"HS256","typ":"JWT"})
    payload = _b64({"sub":"agent-core","aud":"agent-gateway","scope":scope,"run_id":run_id,"exp":int(time.time()) + 300})
    signature = hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
    return f"{header}.{payload}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode()}"

def _b64(value: dict[str, object]) -> str:
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()

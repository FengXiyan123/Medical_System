import logging
import threading
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Security
from pydantic import BaseModel, Field
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from redis.exceptions import AuthenticationError as RedisAuthenticationError

from medical_agent.api.auth import ServiceClaims, ServiceTokenVerifier
from medical_agent.api.chunks import (
    ChunkMutationRequest,
    ChunkPageResponse,
    ChunkResponse,
    ChunkWorkspaceRegistry,
    GenerationManifestResponse,
    GenerationMutationResponse,
    MergeChunkRequest,
    PostgresChunkWorkspaceRegistry,
    SplitChunkRequest,
    mutation_error_status,
)
from medical_agent.api.documents import (
    PreviewChunksRequest,
    PreviewChunksResponse,
    preview_chunks,
)
from medical_agent.api.retrieval_tests import (
    RetrievalTestHttpRequest,
    RetrievalTestResult,
    empty_retrieval_test_service,
    postgres_retrieval_test_service,
)
from medical_agent.api.runs import (
    InternalRunCoordinator,
    PreparedRunResponse,
    PrepareRunRequest,
    SubmittedRunResponse,
    prepare_run,
)
from medical_agent.config import Settings
from medical_agent.persistence.rag_factory import create_rag_repository
from medical_agent.workers.ingestion import IngestionWorker
from medical_agent.workers.ingestion import IngestionJob
from medical_agent.workers.ingestion_runner import create_worker

logger = logging.getLogger(__name__)


def is_redis_authentication_error(error: Exception) -> bool:
    return isinstance(error, RedisAuthenticationError)


def _configure_persistence() -> tuple[object, object]:
    settings = Settings()
    if not settings.effective_postgres_dsn:
        return ChunkWorkspaceRegistry(), empty_retrieval_test_service()
    repository = create_rag_repository(settings)
    return PostgresChunkWorkspaceRegistry(repository), postgres_retrieval_test_service(repository)


@asynccontextmanager
async def lifespan(application: FastAPI):
    settings = Settings()
    workspaces, retrieval = _configure_persistence()
    application.state.chunk_workspaces = workspaces
    application.state.retrieval_tests = retrieval
    worker_status = {"state": "STARTING", "reason": None, "processed": 0}
    application.state.ingestion_worker_status = worker_status
    stopped = threading.Event()
    worker_thread: threading.Thread | None = None
    if not settings.ingestion_worker_enabled:
        worker_status.update(state="DISABLED", reason="INGESTION_WORKER_ENABLED=false")
        logger.warning("ingestion worker is disabled by INGESTION_WORKER_ENABLED")
    elif not settings.effective_postgres_dsn:
        worker_status.update(state="DISABLED", reason="POSTGRES_CONFIGURATION_MISSING")
        logger.error("ingestion worker is disabled: PostgreSQL configuration is missing")
    elif not settings.gateway_service_token_secret:
        worker_status.update(state="DISABLED", reason="GATEWAY_SERVICE_TOKEN_SECRET_MISSING")
        logger.error("ingestion worker is disabled: GATEWAY_SERVICE_TOKEN_SECRET is missing")
    else:
        try:
            from redis import Redis

            redis_client = Redis.from_url(settings.effective_redis_url)
            redis_client.ping()
            worker = IngestionWorker(
                redis=redis_client, repository=create_rag_repository(settings),
                storage_root=settings.medical_storage_root, gateway_base_url=settings.gateway_base_url,
                service_secret=settings.gateway_service_token_secret,
            )
            def consume() -> None:
                worker_status.update(state="RUNNING", reason=None)
                logger.info("ingestion worker started; waiting for Redis Stream jobs")
                while not stopped.is_set():
                    try:
                        processed = worker.process_once(block_ms=1000)
                        if processed:
                            worker_status["processed"] += processed
                            logger.info("ingestion worker processed %s job(s)", processed)
                    except RedisAuthenticationError:
                        worker_status.update(state="FAILED", reason="REDIS_AUTHENTICATION_FAILED")
                        logger.error(
                            "ingestion worker stopped: Redis authentication failed; set REDIS_PASSWORD "
                            "to the password configured on the Redis container"
                        )
                        return
                    except Exception as error:  # Redis/PG may start after Core; retry without terminating Core.
                        worker_status.update(state="RETRYING", reason=type(error).__name__)
                        logger.exception("ingestion worker loop failed")
                        stopped.wait(2)
            worker_thread = threading.Thread(target=consume, name="ingestion-worker", daemon=True)
            worker_thread.start()
        except RedisAuthenticationError:
            worker_status.update(state="FAILED", reason="REDIS_AUTHENTICATION_FAILED")
            logger.error(
                "ingestion worker is disabled: Redis requires authentication; set REDIS_PASSWORD "
                "to the password configured on the Redis container"
            )
        except Exception as error:
            worker_status.update(state="FAILED", reason=type(error).__name__)
            logger.exception("ingestion worker could not start")
    yield
    stopped.set()
    if worker_thread:
        worker_thread.join(timeout=2)

app = FastAPI(title="Medical Agent Core", version="0.1.0", lifespan=lifespan)
app.state.run_coordinator = InternalRunCoordinator()
app.state.chunk_workspaces = ChunkWorkspaceRegistry()
app.state.retrieval_tests = empty_retrieval_test_service()
app.state.ingestion_worker_status = {"state": "NOT_STARTED", "reason": None, "processed": 0}
_bearer = HTTPBearer(auto_error=False)


@app.get("/internal/health/liveness")
def liveness() -> dict[str, str]:
    return {"status": "UP", "service": "agent-core"}


@app.get("/internal/health/ingestion")
def ingestion_health() -> dict[str, object]:
    worker = app.state.ingestion_worker_status
    return {
        "status": "UP" if worker["state"] == "RUNNING" else "DEGRADED",
        "service": "agent-core",
        "ingestionWorker": worker["state"],
        "reason": worker["reason"],
        "processed": worker["processed"],
    }


class DirectIngestionRequest(BaseModel):
    event_id: str = Field(validation_alias="eventId")
    task_id: str = Field(validation_alias="taskId")
    document_id: str = Field(validation_alias="documentId")
    knowledge_base_id: str = Field(validation_alias="knowledgeBaseId")
    storage_key: str = Field(validation_alias="storageKey")
    content_sha256: str = Field(validation_alias="contentSha256")

    def to_job(self) -> IngestionJob:
        return IngestionJob(
            event_id=self.event_id, task_id=self.task_id, document_id=self.document_id,
            knowledge_base_id=self.knowledge_base_id, storage_key=self.storage_key,
            content_sha256=self.content_sha256,
        )


def require_internal_service_ticket(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Security(_bearer)],
) -> ServiceClaims:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=401, detail="service bearer token is required")
    secret = Settings().gateway_service_token_secret
    if not secret:
        raise HTTPException(status_code=503, detail="service token verification is not configured")
    try:
        return ServiceTokenVerifier(secret, audience="agent-core").verify(credentials.credentials)
    except ValueError as error:
        raise HTTPException(status_code=403, detail=str(error)) from error


InternalServiceTicket = Annotated[ServiceClaims, Depends(require_internal_service_ticket)]


def require_scope(ticket: ServiceClaims, scope: str) -> None:
    if scope not in ticket.scopes:
        raise HTTPException(status_code=403, detail=f"service token is missing {scope} scope")


@app.post("/internal/ingestion/jobs", status_code=202)
def execute_ingestion_now(payload: DirectIngestionRequest, ticket: InternalServiceTicket) -> dict[str, str]:
    """Runs one selected job directly when the durable Stream worker is unavailable."""
    require_scope(ticket, "ingestion:execute")
    try:
        create_worker(Settings())._process(payload.to_job())
    except Exception as error:
        logger.exception("direct ingestion failed for document %s", payload.document_id)
        raise HTTPException(status_code=500, detail=f"direct ingestion failed: {type(error).__name__}") from error
    return {"status": "ACCEPTED", "documentId": payload.document_id}


def require_run_ticket(ticket: ServiceClaims, payload: PrepareRunRequest) -> None:
    require_scope(ticket, "runs:write")
    if (ticket.run_id, ticket.user_id, ticket.mode) != (
        payload.run_id,
        payload.user_id,
        payload.request.mode.value,
    ):
        raise HTTPException(status_code=403, detail="service token is not bound to this run")


@app.post("/internal/runs/prepare", response_model=PreparedRunResponse)
def prepare_agent_run(payload: PrepareRunRequest, ticket: InternalServiceTicket) -> PreparedRunResponse:
    require_run_ticket(ticket, payload)
    try:
        return prepare_run(payload)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/internal/runs", response_model=SubmittedRunResponse, status_code=202)
def submit_agent_run(payload: PrepareRunRequest, ticket: InternalServiceTicket) -> SubmittedRunResponse:
    require_run_ticket(ticket, payload)
    try:
        return app.state.run_coordinator.execute_async(payload, gateway_base_url=Settings().gateway_base_url, service_secret=Settings().gateway_service_token_secret or "")
    except ValueError as error:
        raise HTTPException(status_code=409, detail=str(error)) from error


@app.post("/internal/retrieval-tests", response_model=RetrievalTestResult)
def run_retrieval_test(payload: RetrievalTestHttpRequest, ticket: InternalServiceTicket) -> RetrievalTestResult:
    require_scope(ticket, "retrieval:execute")
    try:
        return app.state.retrieval_tests.run(payload.to_domain())
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post("/internal/documents/preview-chunks", response_model=PreviewChunksResponse)
def preview_document_chunks(payload: PreviewChunksRequest, ticket: InternalServiceTicket) -> PreviewChunksResponse:
    require_scope(ticket, "chunks:write")
    try:
        return preview_chunks(payload)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.get("/internal/generations/{generation_id}/chunks", response_model=ChunkPageResponse)
def list_generation_chunks(
    generation_id: str, ticket: InternalServiceTicket, query: str | None = None, enabled: bool | None = None, offset: int = 0, limit: int = 50
) -> ChunkPageResponse:
    require_scope(ticket, "chunks:read")
    try:
        return ChunkPageResponse.from_page(
            app.state.chunk_workspaces.list(generation_id, query=query, enabled=enabled, offset=offset, limit=limit)
        )
    except ValueError as error:
        raise HTTPException(status_code=mutation_error_status(error), detail=str(error)) from error


@app.get("/internal/generations/{generation_id}/chunks/{chunk_id}", response_model=ChunkResponse)
def generation_chunk_detail(generation_id: str, chunk_id: str, ticket: InternalServiceTicket) -> ChunkResponse:
    require_scope(ticket, "chunks:read")
    try:
        return ChunkResponse.from_chunk(app.state.chunk_workspaces.get(generation_id, chunk_id))
    except ValueError as error:
        raise HTTPException(status_code=mutation_error_status(error), detail=str(error)) from error


@app.patch("/internal/generations/{generation_id}/chunks/{chunk_id}", response_model=GenerationMutationResponse)
def update_generation_chunk(
    generation_id: str, chunk_id: str, request: ChunkMutationRequest, ticket: InternalServiceTicket
) -> GenerationMutationResponse:
    require_scope(ticket, "chunks:write")
    try:
        return GenerationMutationResponse.from_generation(app.state.chunk_workspaces.mutate(generation_id, chunk_id, request))
    except ValueError as error:
        raise HTTPException(status_code=mutation_error_status(error), detail=str(error)) from error


@app.post("/internal/generations/{generation_id}/chunks/{chunk_id}/split", response_model=GenerationMutationResponse)
def split_generation_chunk(generation_id: str, chunk_id: str, request: SplitChunkRequest, ticket: InternalServiceTicket) -> GenerationMutationResponse:
    require_scope(ticket, "chunks:write")
    try:
        return GenerationMutationResponse.from_generation(app.state.chunk_workspaces.split(generation_id, request, chunk_id))
    except ValueError as error:
        raise HTTPException(status_code=mutation_error_status(error), detail=str(error)) from error


@app.post("/internal/generations/{generation_id}/chunks:merge", response_model=GenerationMutationResponse)
def merge_generation_chunks(generation_id: str, request: MergeChunkRequest, ticket: InternalServiceTicket) -> GenerationMutationResponse:
    require_scope(ticket, "chunks:write")
    try:
        return GenerationMutationResponse.from_generation(app.state.chunk_workspaces.merge(generation_id, request))
    except ValueError as error:
        raise HTTPException(status_code=mutation_error_status(error), detail=str(error)) from error


@app.post("/internal/generations/{generation_id}/index", response_model=GenerationManifestResponse)
def index_generation(generation_id: str, ticket: InternalServiceTicket) -> GenerationManifestResponse:
    require_scope(ticket, "chunks:write")
    try:
        result = app.state.chunk_workspaces.index(generation_id)
        return GenerationManifestResponse(
            generation_id=result.generation_id, document_id=result.document_id, build_status=result.state, manifest_hash=result.manifest_hash,
            chunk_count=result.chunk_count, embedding_profile_id=result.embedding_profile_id, embedding_dimension=1024,
        )
    except ValueError as error:
        raise HTTPException(status_code=mutation_error_status(error), detail=str(error)) from error


@app.get("/internal/generations/{generation_id}/manifest", response_model=GenerationManifestResponse)
def generation_manifest(generation_id: str, ticket: InternalServiceTicket) -> GenerationManifestResponse:
    require_scope(ticket, "chunks:read")
    try:
        result = app.state.chunk_workspaces.manifest(generation_id)
        return GenerationManifestResponse(
            generation_id=result.generation_id, document_id=result.document_id, build_status=result.state, manifest_hash=result.manifest_hash,
            chunk_count=result.chunk_count, embedding_profile_id=result.embedding_profile_id, embedding_dimension=1024,
        )
    except ValueError as error:
        raise HTTPException(status_code=mutation_error_status(error), detail=str(error)) from error

"""Redis-stream worker that turns gateway uploads into pgvector generations."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from redis import Redis

from medical_agent.ingestion.chunking import (
    ChunkConfig,
    ConservativeTextTokenCounter,
    chunk_sections,
)
from medical_agent.ingestion.draft_editor import DraftChunk, DraftGeneration
from medical_agent.ingestion.parsing import DocumentParserRegistry, DocumentParseStatus
from medical_agent.persistence.pg_rag import PostgresRagRepository

logger = logging.getLogger(__name__)
STREAM = "medical:default:ingest.jobs"
GROUP = "agent-core-ingestion"


@dataclass(frozen=True, slots=True)
class IngestionJob:
    event_id: str
    task_id: str
    document_id: str
    knowledge_base_id: str
    storage_key: str
    content_sha256: str

    @classmethod
    def from_fields(cls, fields: dict[bytes, bytes]) -> IngestionJob:
        raw = fields.get(b"payload")
        if raw is None:
            raise ValueError("ingestion event payload is missing")
        value = json.loads(raw)
        return cls(
            event_id=_required(value, "eventId"), task_id=_required(value, "taskId"),
            document_id=_required(value, "documentId"), knowledge_base_id=_required(value, "knowledgeBaseId"),
            storage_key=_required(value, "storageKey"), content_sha256=_required(value, "contentSha256"),
        )


class IngestionWorker:
    def __init__(self, *, redis: Redis[bytes], repository: PostgresRagRepository, storage_root: Path,
                 gateway_base_url: str, service_secret: str, consumer: str = "agent-core-1") -> None:
        self._redis = redis
        self._repository = repository
        self._storage_root = storage_root.resolve()
        self._gateway = gateway_base_url.rstrip("/")
        self._secret = service_secret
        self._consumer = consumer
        self._parser = DocumentParserRegistry()

    def process_once(self, *, block_ms: int = 1000) -> int:
        self._ensure_group()
        # Redis 6.0 has no XAUTOCLAIM.  Re-reading this consumer's pending
        # entries first gives failed callbacks a durable retry path after a
        # restart without acknowledging an event before Java accepts it.
        messages = self._redis.xreadgroup(GROUP, self._consumer, {STREAM: "0"}, count=1)
        if not messages:
            messages = self._redis.xreadgroup(
                GROUP, self._consumer, {STREAM: ">"}, count=1, block=block_ms
            )
        processed = 0
        for _, entries in messages:
            for message_id, fields in entries:
                try:
                    self._process(IngestionJob.from_fields(fields))
                    self._redis.xack(STREAM, GROUP, message_id)
                    processed += 1
                except Exception:  # leave unacknowledged so it remains recoverable.
                    logger.exception("ingestion message %s failed", message_id)
        return processed

    def _process(self, job: IngestionJob) -> None:
        try:
            self._callback(job, status="RUNNING", stage="PARSE", progress=10)
            source = self._safe_file(job.storage_key)
            content = source.read_bytes()
            actual_checksum = hashlib.sha256(content).hexdigest()
            if not hmac.compare_digest(actual_checksum, job.content_sha256):
                raise ValueError("UPLOADED_FILE_CHECKSUM_MISMATCH")
            parsed = self._parser.parse(filename=source.name, content=content)
            if parsed.status is not DocumentParseStatus.PARSED:
                raise ValueError(parsed.error_code or "DOCUMENT_PARSE_FAILED")
            self._callback(job, status="RUNNING", stage="CHUNK", progress=40)
            chunks = chunk_sections(
                parsed.as_chunk_sections(), config=ChunkConfig(), token_counter=ConservativeTextTokenCounter(),
            )
            if not chunks:
                raise ValueError("NO_EXTRACTABLE_TEXT")
            generation_id = str(uuid5(NAMESPACE_URL, f"medical-ingestion:{job.task_id}"))
            draft = DraftGeneration(
                id=generation_id, document_id=job.document_id, state="DRAFT", edit_revision=0,
                chunks=tuple(DraftChunk(
                    id=str(uuid5(NAMESPACE_URL, f"{generation_id}:{chunk.ordinal}")), ordinal=chunk.ordinal,
                    content=chunk.content, enabled=True, page_start=chunk.page_number,
                    section_path=" / ".join(chunk.source.heading_path) if chunk.source and chunk.source.heading_path else chunk.title,
                ) for chunk in chunks),
            )
            self._callback(job, status="RUNNING", stage="EMBED", progress=70)
            self._repository.create_generation(
                draft, knowledge_base_id=job.knowledge_base_id, source_checksum=job.content_sha256,
                parser_name=parsed.format.value.lower(), parser_version="v1", version_no=1,
                chunking_config={"max_tokens": 600, "overlap_tokens": 100, "tokenizer": "unicode-conservative-v1"},
            )
            result = self._repository.build_index(generation_id)
            self._callback(job, status="SUCCEEDED", generation_id=generation_id,
                           manifest_hash=result.manifest_hash, chunk_count=result.chunk_count,
                           embedding_profile_id=result.embedding_profile_id, embedding_dimension=1024)
        except Exception as error:
            self._callback(job, status="FAILED", error_code=_error_code(error))
            raise

    def _callback(self, job: IngestionJob, *, status: str, stage: str | None = None,
                  progress: int | None = None, generation_id: str | None = None,
                  manifest_hash: str | None = None, chunk_count: int | None = None,
                  embedding_profile_id: str | None = None, embedding_dimension: int | None = None,
                  error_code: str | None = None) -> None:
        payload = {"eventId": job.event_id, "taskId": job.task_id, "documentId": job.document_id,
                   "status": status, "stage": stage, "progress": progress,
                   "generationId": generation_id, "manifestHash": manifest_hash,
                   "chunkCount": chunk_count, "embeddingProfileId": embedding_profile_id,
                   "embeddingDimension": embedding_dimension, "errorCode": error_code}
        request = urllib.request.Request(
            f"{self._gateway}/api/internal/v1/ingestion/completions",
            data=json.dumps(payload).encode(), method="POST",
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {_ticket(self._secret)}"},
        )
        with urllib.request.urlopen(request, timeout=10):
            pass

    def _ensure_group(self) -> None:
        try:
            self._redis.xgroup_create(STREAM, GROUP, id="0", mkstream=True)
        except Exception as error:
            if "BUSYGROUP" not in str(error):
                raise

    def _safe_file(self, storage_key: str) -> Path:
        value = (self._storage_root / storage_key).resolve()
        if not value.is_relative_to(self._storage_root):
            raise ValueError("INVALID_STORAGE_KEY")
        return value


def _required(value: object, key: str) -> str:
    if not isinstance(value, dict) or not isinstance(value.get(key), str) or not value[key].strip():
        raise ValueError(f"ingestion event {key} is missing")
    return value[key]


def _error_code(error: Exception) -> str:
    value = str(error).strip() or "INGESTION_FAILED"
    return value[:128]


def _ticket(secret: str) -> str:
    header = _b64({"alg": "HS256", "typ": "JWT"})
    payload = _b64({"sub": "agent-core", "aud": "agent-gateway", "scope": "ingestion:callback",
                    "exp": int(time.time()) + 300})
    signature = hmac.new(secret.encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
    return f"{header}.{payload}.{base64.urlsafe_b64encode(signature).rstrip(b'=').decode()}"


def _b64(value: dict[str, object]) -> str:
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=").decode()

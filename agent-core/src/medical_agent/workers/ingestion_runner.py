"""Standalone entry point for document ingestion when it is operated separately from FastAPI."""

from __future__ import annotations

import logging
import time

from redis import Redis

from medical_agent.config import Settings
from medical_agent.persistence.rag_factory import create_rag_repository
from medical_agent.workers.ingestion import IngestionWorker


def create_worker(settings: Settings) -> IngestionWorker:
    if not settings.ingestion_worker_enabled:
        raise RuntimeError("INGESTION_WORKER_ENABLED=false")
    if not settings.effective_postgres_dsn:
        raise RuntimeError("PostgreSQL ingestion configuration is missing")
    if not settings.gateway_service_token_secret:
        raise RuntimeError("GATEWAY_SERVICE_TOKEN_SECRET is missing")
    redis = Redis.from_url(settings.effective_redis_url)
    redis.ping()
    repository = create_rag_repository(settings)
    repository.ping()
    return IngestionWorker(
        redis=redis, repository=repository, storage_root=settings.medical_storage_root,
        gateway_base_url=settings.gateway_base_url, service_secret=settings.gateway_service_token_secret,
    )


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    worker = create_worker(Settings())
    logging.getLogger(__name__).info("standalone ingestion worker started")
    while True:
        processed = worker.process_once(block_ms=1000)
        if processed:
            logging.getLogger(__name__).info("processed %s ingestion job(s)", processed)
        time.sleep(0.05)


if __name__ == "__main__":
    main()

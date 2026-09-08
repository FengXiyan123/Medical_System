"""Build a RAG repository with mock or real DashScope embeddings."""

from medical_agent.config import ModelMode, Settings
from medical_agent.ingestion.indexing import DeterministicEmbedder
from medical_agent.models.embeddings import DashScopeEmbeddingModel
from medical_agent.persistence.pg_rag import PostgresRagRepository


def create_rag_repository(settings: Settings) -> PostgresRagRepository:
    dsn = settings.effective_postgres_dsn
    if not dsn:
        raise ValueError("PostgreSQL is not configured")
    if settings.model_mode is ModelMode.REAL:
        embedder = DashScopeEmbeddingModel(
            workspace_id=settings.dashscope_workspace_id or "", region=settings.dashscope_region,
            api_key=settings.dashscope_api_key or "", base_url=settings.dashscope_base_url,
        )
    else:
        embedder = DeterministicEmbedder(1024, "mock-text-embedding-v4-1024")
    return PostgresRagRepository(dsn, embedder=embedder)

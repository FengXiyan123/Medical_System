CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS rag;
CREATE SCHEMA IF NOT EXISTS execution;
CREATE SCHEMA IF NOT EXISTS langgraph_checkpoint;

CREATE TABLE IF NOT EXISTS rag.document_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    source_checksum CHAR(64) NOT NULL,
    parser_name VARCHAR(128) NOT NULL,
    parser_version VARCHAR(64) NOT NULL,
    chunking_config JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, version_no)
);

CREATE TABLE IF NOT EXISTS rag.generation (
    generation_id UUID PRIMARY KEY,
    document_version_id UUID NOT NULL REFERENCES rag.document_version(id),
    document_id UUID NOT NULL,
    knowledge_base_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    edit_revision BIGINT NOT NULL DEFAULT 0,
    chunk_manifest_hash CHAR(64) NULL,
    embedding_profile_id VARCHAR(128) NULL,
    embedding_dimension INTEGER NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    build_ready_at TIMESTAMPTZ NULL,
    CHECK (state IN ('DRAFT', 'INDEXING', 'BUILD_READY', 'ACTIVE', 'RETIRED', 'BUILD_FAILED')),
    CHECK (edit_revision >= 0),
    CHECK (embedding_dimension IS NULL OR embedding_dimension > 0)
);

CREATE TABLE IF NOT EXISTS rag.chunk (
    chunk_id UUID PRIMARY KEY,
    generation_id UUID NOT NULL REFERENCES rag.generation(generation_id),
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    source_text TEXT NULL,
    page_start INTEGER NULL,
    page_end INTEGER NULL,
    section_path TEXT NULL,
    source_offsets JSONB NOT NULL DEFAULT '{}'::jsonb,
    token_count INTEGER NOT NULL,
    tokenizer_version VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    content_hash CHAR(64) NOT NULL,
    manually_edited BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (generation_id, ordinal),
    CHECK (ordinal >= 0),
    CHECK (token_count >= 0),
    CHECK (page_start IS NULL OR page_start > 0),
    CHECK (page_end IS NULL OR page_start IS NULL OR page_end >= page_start)
);

CREATE TABLE IF NOT EXISTS rag.chunk_embedding_v1 (
    generation_id UUID NOT NULL REFERENCES rag.generation(generation_id),
    chunk_id UUID NOT NULL REFERENCES rag.chunk(chunk_id),
    embedding_profile_id VARCHAR(128) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (generation_id, chunk_id, embedding_profile_id)
);

CREATE INDEX IF NOT EXISTS idx_rag_chunk_generation_enabled
    ON rag.chunk (generation_id, enabled, ordinal);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_v1_hnsw
    ON rag.chunk_embedding_v1 USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS execution.retrieval_snapshot (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    chunk_id UUID NOT NULL REFERENCES rag.chunk(chunk_id),
    stage VARCHAR(32) NOT NULL,
    score DOUBLE PRECISION,
    selection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, chunk_id, stage),
    CHECK (stage IN ('RETRIEVED', 'RERANKED', 'SELECTED', 'CITED'))
);

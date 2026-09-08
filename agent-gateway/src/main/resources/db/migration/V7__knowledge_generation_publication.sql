CREATE TABLE knowledge_generation (
    id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    generation_no INTEGER NOT NULL,
    build_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    publication_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    edit_revision BIGINT NOT NULL DEFAULT 0,
    chunk_config_json JSON NOT NULL,
    embedding_profile_id VARCHAR(128) NULL,
    embedding_dimension INTEGER NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    content_manifest_hash CHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    indexed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_generation_document_no (document_id, generation_no),
    KEY idx_knowledge_generation_document_build (document_id, build_status),
    CONSTRAINT fk_knowledge_generation_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT ck_knowledge_generation_build_status CHECK (build_status IN ('DRAFT', 'INDEXING', 'BUILD_READY', 'BUILD_FAILED')),
    CONSTRAINT ck_knowledge_generation_publication_status CHECK (publication_status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_knowledge_generation_revision CHECK (edit_revision >= 0),
    CONSTRAINT ck_knowledge_generation_chunk_count CHECK (chunk_count >= 0),
    CONSTRAINT ck_knowledge_generation_dimension CHECK (embedding_dimension IS NULL OR embedding_dimension > 0)
);

ALTER TABLE knowledge_document ADD COLUMN active_generation_id CHAR(36) NULL;
ALTER TABLE knowledge_document ADD CONSTRAINT fk_knowledge_document_active_generation
    FOREIGN KEY (active_generation_id) REFERENCES knowledge_generation(id);
CREATE UNIQUE INDEX uk_knowledge_document_active_generation ON knowledge_document(active_generation_id);

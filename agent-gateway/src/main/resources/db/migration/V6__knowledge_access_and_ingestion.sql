ALTER TABLE knowledge_base ADD COLUMN access_scope VARCHAR(32) NOT NULL DEFAULT 'ALL_AUTHENTICATED';
ALTER TABLE knowledge_base ADD CONSTRAINT ck_knowledge_base_access_scope
    CHECK (access_scope IN ('ALL_AUTHENTICATED', 'ASSIGNED_USERS'));

CREATE TABLE knowledge_base_user_grant (
    knowledge_base_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    granted_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (knowledge_base_id, user_id),
    KEY idx_knowledge_base_user_grant_user (user_id, knowledge_base_id),
    CONSTRAINT fk_knowledge_base_user_grant_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT fk_knowledge_base_user_grant_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_knowledge_base_user_grant_granter FOREIGN KEY (granted_by) REFERENCES app_user(id)
);

CREATE TABLE ingest_job (
    id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_no INTEGER NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) NULL,
    lease_until TIMESTAMP(3) NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    progress INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(128) NULL,
    next_retry_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ingest_job_dispatch (status, next_retry_at, created_at),
    KEY idx_ingest_job_document (document_id, created_at),
    CONSTRAINT fk_ingest_job_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT ck_ingest_job_stage CHECK (stage IN ('PARSE', 'CHUNK', 'EMBED')),
    CONSTRAINT ck_ingest_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED')),
    CONSTRAINT ck_ingest_job_progress CHECK (progress BETWEEN 0 AND 100)
);

ALTER TABLE outbox_event ADD COLUMN delivery_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE outbox_event ADD COLUMN last_error VARCHAR(500) NULL;
ALTER TABLE outbox_event ADD COLUMN stream_id VARCHAR(128) NULL;

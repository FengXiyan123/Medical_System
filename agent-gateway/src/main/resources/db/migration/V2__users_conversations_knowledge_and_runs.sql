CREATE TABLE app_user (
    id CHAR(36) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE conversation (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conversation_user_updated (user_id, updated_at DESC),
    CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT ck_conversation_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE chat_message (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    run_id CHAR(36) NULL,
    role VARCHAR(32) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    citation_manifest JSON NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_chat_message_conversation_created (conversation_id, created_at),
    KEY idx_chat_message_run (run_id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT ck_chat_message_role CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM'))
);

CREATE TABLE knowledge_base (
    id CHAR(36) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_base_name (name),
    CONSTRAINT fk_knowledge_base_creator FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT ck_knowledge_base_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE TABLE knowledge_document (
    id CHAR(36) NOT NULL,
    knowledge_base_id CHAR(36) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    latest_version INTEGER NOT NULL DEFAULT 0,
    uploaded_by CHAR(36) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_document_content (knowledge_base_id, content_sha256),
    KEY idx_knowledge_document_base_status (knowledge_base_id, status),
    CONSTRAINT fk_knowledge_document_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT fk_knowledge_document_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user(id),
    CONSTRAINT ck_knowledge_document_status CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'ARCHIVED'))
);

CREATE TABLE agent_run (
    id CHAR(36) NOT NULL,
    trace_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    route_name VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    question MEDIUMTEXT NOT NULL,
    answer_message_id CHAR(36) NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    usage_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_trace (trace_id),
    KEY idx_agent_run_user_created (user_id, created_at DESC),
    KEY idx_agent_run_conversation_created (conversation_id, created_at),
    CONSTRAINT fk_agent_run_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_agent_run_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id),
    CONSTRAINT ck_agent_run_mode CHECK (mode IN ('AUTO_KB', 'MANUAL_KB', 'AGENT')),
    CONSTRAINT ck_agent_run_status CHECK (status IN ('CREATED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_agent_run_usage_status CHECK (usage_status IN ('PENDING', 'REPORTED', 'UNKNOWN'))
);

CREATE TABLE model_invocation_trace (
    id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    invocation_id CHAR(36) NOT NULL,
    attempt_no INTEGER NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    request_snapshot JSON NOT NULL,
    response_snapshot JSON NULL,
    input_tokens BIGINT NULL,
    output_tokens BIGINT NULL,
    usage_source VARCHAR(32) NOT NULL,
    latency_ms BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invocation_attempt (invocation_id, attempt_no),
    KEY idx_model_invocation_trace_run (run_id, created_at),
    CONSTRAINT fk_model_invocation_trace_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT ck_model_invocation_usage_source CHECK (usage_source IN ('PROVIDER_REPORTED', 'UNKNOWN')),
    CONSTRAINT ck_model_invocation_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED'))
);

CREATE TABLE run_knowledge_trace (
    id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    chunk_id CHAR(36) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    score DECIMAL(8, 6) NULL,
    selection_reason VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_knowledge_stage (run_id, chunk_id, stage),
    KEY idx_run_knowledge_trace_run (run_id, stage),
    CONSTRAINT fk_run_knowledge_trace_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT ck_run_knowledge_stage CHECK (stage IN ('RETRIEVED', 'RERANKED', 'SELECTED', 'CITED'))
);

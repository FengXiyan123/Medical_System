-- M5 projections deliberately keep credentials outside the database. credential_ref is a vault/env reference only.
ALTER TABLE run_knowledge_trace ADD COLUMN knowledge_base_id CHAR(36) NULL;
ALTER TABLE run_knowledge_trace ADD COLUMN document_id CHAR(36) NULL;
ALTER TABLE run_knowledge_trace ADD COLUMN generation_id CHAR(36) NULL;
ALTER TABLE run_knowledge_trace ADD COLUMN chunk_snapshot JSON NULL;
ALTER TABLE run_knowledge_trace DROP CHECK ck_run_knowledge_stage;
ALTER TABLE run_knowledge_trace ADD CONSTRAINT ck_run_knowledge_stage CHECK (stage IN ('RETRIEVED', 'RERANKED', 'SELECTED', 'CONTEXT_INCLUDED', 'CITED'));

CREATE TABLE run_trace_span (
    id CHAR(36) NOT NULL, run_id CHAR(36) NOT NULL, parent_span_id CHAR(36) NULL,
    kind VARCHAR(48) NOT NULL, node_name VARCHAR(96) NOT NULL, attempt_no INTEGER NOT NULL DEFAULT 1,
    started_at TIMESTAMP(3) NOT NULL, finished_at TIMESTAMP(3) NULL, status VARCHAR(32) NOT NULL,
    error_code VARCHAR(128) NULL, input_summary JSON NULL, output_summary JSON NULL,
    PRIMARY KEY (id), KEY idx_run_trace_span_waterfall (run_id, started_at),
    CONSTRAINT fk_run_trace_span_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

CREATE TABLE usage_projection (
    invocation_id CHAR(36) NOT NULL, attempt_no INTEGER NOT NULL, run_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL, mode VARCHAR(32) NOT NULL, purpose VARCHAR(48) NOT NULL,
    provider VARCHAR(64) NOT NULL, model VARCHAR(128) NOT NULL, input_tokens BIGINT NULL,
    output_tokens BIGINT NULL, usage_source VARCHAR(32) NOT NULL, estimated_cost DECIMAL(20,8) NULL,
    currency VARCHAR(16) NULL, price_snapshot JSON NOT NULL, observed_at TIMESTAMP(3) NOT NULL,
    synced_at TIMESTAMP(3) NOT NULL, PRIMARY KEY (invocation_id, attempt_no),
    KEY idx_usage_projection_report (user_id, observed_at, model, mode),
    CONSTRAINT fk_usage_projection_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT ck_usage_projection_source CHECK (usage_source IN ('PROVIDER_REPORTED','ESTIMATED','UNKNOWN'))
);

CREATE TABLE audit_event (
    id CHAR(36) NOT NULL, actor_id CHAR(36) NULL, action VARCHAR(96) NOT NULL,
    target_type VARCHAR(64) NOT NULL, target_id CHAR(36) NULL, trace_id CHAR(36) NULL,
    redacted_diff JSON NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id), KEY idx_audit_event_created (created_at), KEY idx_audit_event_actor (actor_id, created_at)
);

CREATE TABLE model_profile (
    id CHAR(36) NOT NULL, name VARCHAR(128) NOT NULL, provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL, region VARCHAR(64) NULL, endpoint VARCHAR(512) NULL,
    credential_ref VARCHAR(512) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id), UNIQUE KEY uk_model_profile_name (name)
);
CREATE TABLE prompt_version (
    id CHAR(36) NOT NULL, name VARCHAR(128) NOT NULL, version INTEGER NOT NULL, template MEDIUMTEXT NOT NULL,
    template_hash CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_prompt_version (name, version),
    CONSTRAINT ck_prompt_version_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED'))
);
CREATE TABLE runtime_policy (
    id CHAR(36) NOT NULL, policy_type VARCHAR(32) NOT NULL, version INTEGER NOT NULL,
    configuration JSON NOT NULL, status VARCHAR(32) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_runtime_policy_version (policy_type, version),
    CONSTRAINT ck_runtime_policy_type CHECK (policy_type IN ('BUDGET','RETRIEVAL')),
    CONSTRAINT ck_runtime_policy_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED'))
);

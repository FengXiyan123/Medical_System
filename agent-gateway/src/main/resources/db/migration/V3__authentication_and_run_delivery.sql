ALTER TABLE app_user ADD COLUMN auth_version INTEGER NOT NULL DEFAULT 0;

CREATE TABLE refresh_token (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    revoked_at TIMESTAMP(3) NULL,
    replaced_by_id CHAR(36) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id), UNIQUE KEY uk_refresh_token_hash (token_hash),
    KEY idx_refresh_token_user (user_id),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);

ALTER TABLE agent_run ADD COLUMN idempotency_key VARCHAR(128) NULL;
ALTER TABLE agent_run ADD COLUMN request_hash CHAR(64) NULL;
ALTER TABLE agent_run ADD COLUMN active_lock TINYINT GENERATED ALWAYS AS
    (CASE WHEN status IN ('CREATED', 'RUNNING') THEN 1 ELSE NULL END) STORED;
ALTER TABLE agent_run ADD UNIQUE KEY uk_agent_run_user_idempotency (user_id, idempotency_key);
ALTER TABLE agent_run ADD UNIQUE KEY uk_agent_run_conversation_active (conversation_id, active_lock);

CREATE TABLE outbox_event (
    id CHAR(36) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    dispatched_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id), KEY idx_outbox_event_pending (dispatched_at, created_at)
);

CREATE TABLE inbox_event (
    event_id CHAR(36) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id)
);

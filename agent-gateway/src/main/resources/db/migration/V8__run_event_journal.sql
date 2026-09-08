CREATE TABLE agent_run_event (
    event_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSON NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_agent_run_event_sequence (run_id, sequence_no),
    KEY idx_agent_run_event_replay (run_id, sequence_no),
    CONSTRAINT fk_agent_run_event_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT ck_agent_run_event_sequence CHECK (sequence_no > 0)
);

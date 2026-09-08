ALTER TABLE agent_run ADD COLUMN request_message_id CHAR(36) NULL;
ALTER TABLE agent_run ADD COLUMN regeneration_of_run_id CHAR(36) NULL;
ALTER TABLE agent_run ADD CONSTRAINT fk_agent_run_regeneration
    FOREIGN KEY (regeneration_of_run_id) REFERENCES agent_run(id);

CREATE TABLE agent_run_knowledge_base (
    run_id CHAR(36) NOT NULL,
    knowledge_base_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (run_id, knowledge_base_id),
    CONSTRAINT fk_agent_run_knowledge_base_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT fk_agent_run_knowledge_base_knowledge_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id)
);

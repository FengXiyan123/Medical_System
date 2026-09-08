ALTER TABLE agent_run
    ADD COLUMN finish_reason VARCHAR(128) NULL AFTER status;

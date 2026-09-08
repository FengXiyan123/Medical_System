CREATE TABLE feedback (
    answer_message_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    rating VARCHAR(8) NOT NULL,
    reason VARCHAR(500) NULL,
    comment VARCHAR(2000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (answer_message_id, user_id),
    KEY idx_feedback_run (run_id, updated_at),
    CONSTRAINT fk_feedback_answer FOREIGN KEY (answer_message_id) REFERENCES chat_message(id),
    CONSTRAINT fk_feedback_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT ck_feedback_rating CHECK (rating IN ('UP', 'DOWN'))
);

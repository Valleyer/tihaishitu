CREATE TABLE question_report (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    attempt_id CHAR(36) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    comment VARCHAR(1000) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'open',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_question_report_learner_attempt UNIQUE (learner_id, attempt_id),
    CONSTRAINT fk_question_report_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_question_report_question FOREIGN KEY (question_id)
        REFERENCES question_resource(id),
    CONSTRAINT fk_question_report_attempt FOREIGN KEY (attempt_id)
        REFERENCES study_attempt(id) ON DELETE CASCADE
);

CREATE INDEX idx_question_report_status_created ON question_report(status, created_at);
CREATE INDEX idx_question_report_question_created ON question_report(question_id, created_at);

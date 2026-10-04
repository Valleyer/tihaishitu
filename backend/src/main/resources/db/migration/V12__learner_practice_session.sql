CREATE TABLE learner_practice_session (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    intent VARCHAR(24) NOT NULL,
    target_knowledge_point_id CHAR(36) NOT NULL,
    source_question_id CHAR(36) NULL,
    current_attempt_id CHAR(36) NULL,
    status VARCHAR(24) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ended_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_practice_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_practice_target FOREIGN KEY (target_knowledge_point_id)
        REFERENCES global_knowledge_point(id),
    CONSTRAINT fk_practice_source_question FOREIGN KEY (source_question_id)
        REFERENCES question_resource(id)
);

CREATE TABLE learner_practice_scope (
    session_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    PRIMARY KEY (session_id, knowledge_point_id),
    CONSTRAINT fk_practice_scope_session FOREIGN KEY (session_id)
        REFERENCES learner_practice_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_practice_scope_point FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id)
);

ALTER TABLE study_attempt ADD COLUMN practice_session_id CHAR(36) NULL;
ALTER TABLE study_attempt ADD CONSTRAINT fk_attempt_practice_session
    FOREIGN KEY (practice_session_id) REFERENCES learner_practice_session(id);
CREATE INDEX idx_attempt_practice_created
    ON study_attempt(practice_session_id, created_at);

ALTER TABLE learner_practice_session ADD CONSTRAINT fk_practice_current_attempt
    FOREIGN KEY (current_attempt_id) REFERENCES study_attempt(id);

ALTER TABLE learner_diagnosis_session MODIFY world_id VARCHAR(64) NULL;
ALTER TABLE learner_diagnosis_session ADD COLUMN practice_session_id CHAR(36) NULL;
ALTER TABLE learner_diagnosis_session ADD CONSTRAINT fk_diagnosis_practice_session
    FOREIGN KEY (practice_session_id) REFERENCES learner_practice_session(id);
CREATE INDEX idx_diagnosis_practice_status
    ON learner_diagnosis_session(practice_session_id, status, updated_at);

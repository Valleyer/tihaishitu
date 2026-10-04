CREATE TABLE learner_diagnosis_session (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    world_id VARCHAR(64) NOT NULL,
    root_attempt_id CHAR(36) NOT NULL,
    target_knowledge_point_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    resolution VARCHAR(32) NULL,
    has_unavailable_dependency BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_diagnosis_root_attempt UNIQUE (root_attempt_id),
    CONSTRAINT fk_diagnosis_learner FOREIGN KEY (learner_id) REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_root_attempt FOREIGN KEY (root_attempt_id) REFERENCES study_attempt(id),
    CONSTRAINT fk_diagnosis_target FOREIGN KEY (target_knowledge_point_id) REFERENCES global_knowledge_point(id)
);

CREATE TABLE learner_diagnosis_dependency (
    diagnosis_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (diagnosis_id, knowledge_point_id),
    CONSTRAINT fk_diagnosis_dependency_session FOREIGN KEY (diagnosis_id)
        REFERENCES learner_diagnosis_session(id) ON DELETE CASCADE,
    CONSTRAINT fk_diagnosis_dependency_point FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id)
);

ALTER TABLE study_attempt ADD COLUMN diagnosis_session_id CHAR(36) NULL;
ALTER TABLE study_attempt ADD COLUMN diagnosis_role VARCHAR(32) NULL;
ALTER TABLE study_attempt ADD CONSTRAINT fk_attempt_diagnosis_session
    FOREIGN KEY (diagnosis_session_id) REFERENCES learner_diagnosis_session(id);

CREATE INDEX idx_diagnosis_learner_status
    ON learner_diagnosis_session(learner_id, status, updated_at);

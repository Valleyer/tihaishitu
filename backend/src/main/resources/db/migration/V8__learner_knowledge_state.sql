ALTER TABLE study_attempt ADD COLUMN target_knowledge_point_id CHAR(36) NULL;
ALTER TABLE study_attempt ADD COLUMN evidence_mode VARCHAR(16) NULL;
ALTER TABLE study_attempt ADD COLUMN question_difficulty INT NULL;
ALTER TABLE study_attempt ADD CONSTRAINT fk_attempt_target_knowledge
    FOREIGN KEY (target_knowledge_point_id) REFERENCES global_knowledge_point(id);

CREATE TABLE learner_knowledge_state (
    learner_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    mastery_score DECIMAL(5,2) NOT NULL,
    stability_days DECIMAL(8,2) NOT NULL,
    target_difficulty INT NOT NULL,
    evidence_count INT NOT NULL,
    correct_streak INT NOT NULL,
    wrong_streak INT NOT NULL,
    last_outcome VARCHAR(16) NULL,
    last_evidence_at TIMESTAMP(6) NULL,
    last_correct_at TIMESTAMP(6) NULL,
    model_version VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (learner_id, knowledge_point_id),
    CONSTRAINT fk_knowledge_state_learner FOREIGN KEY (learner_id) REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_state_point FOREIGN KEY (knowledge_point_id) REFERENCES global_knowledge_point(id)
);

CREATE TABLE learner_knowledge_evidence (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    attempt_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    world_id VARCHAR(64) NULL,
    outcome VARCHAR(16) NOT NULL,
    grading_source VARCHAR(16) NOT NULL,
    evidence_mode VARCHAR(16) NOT NULL,
    question_difficulty INT NOT NULL,
    quality DECIMAL(5,4) NOT NULL,
    learning_rate DECIMAL(6,5) NOT NULL,
    effective_mastery_before DECIMAL(5,2) NOT NULL,
    mastery_after DECIMAL(5,2) NOT NULL,
    stability_before DECIMAL(8,2) NOT NULL,
    stability_after DECIMAL(8,2) NOT NULL,
    target_difficulty_before INT NOT NULL,
    target_difficulty_after INT NOT NULL,
    model_version VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_knowledge_evidence_attempt UNIQUE (attempt_id),
    CONSTRAINT fk_knowledge_evidence_learner FOREIGN KEY (learner_id) REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_evidence_point FOREIGN KEY (knowledge_point_id) REFERENCES global_knowledge_point(id),
    CONSTRAINT fk_knowledge_evidence_attempt FOREIGN KEY (attempt_id) REFERENCES study_attempt(id)
);
CREATE INDEX idx_knowledge_evidence_timeline
    ON learner_knowledge_evidence(learner_id, knowledge_point_id, occurred_at);

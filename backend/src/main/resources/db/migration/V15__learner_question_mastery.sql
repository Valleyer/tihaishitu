ALTER TABLE learner_knowledge_state MODIFY COLUMN model_version VARCHAR(32) NOT NULL;
ALTER TABLE learner_knowledge_evidence MODIFY COLUMN model_version VARCHAR(32) NOT NULL;

CREATE TABLE learner_question_mastery (
    learner_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    score DECIMAL(5,1) NOT NULL DEFAULT 0,
    first_correct_at TIMESTAMP(6) NULL,
    last_correct_at TIMESTAMP(6) NULL,
    last_reward_date DATE NULL,
    last_decay_date DATE NULL,
    last_assessment VARCHAR(16) NULL,
    last_attempt_at TIMESTAMP(6) NULL,
    decay_frozen BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (learner_id, knowledge_point_id, question_id),
    CONSTRAINT fk_question_mastery_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_question_mastery_knowledge FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id),
    CONSTRAINT fk_question_mastery_question FOREIGN KEY (question_id)
        REFERENCES question_resource(id)
);

CREATE INDEX idx_question_mastery_knowledge
    ON learner_question_mastery(learner_id, knowledge_point_id, last_attempt_at);

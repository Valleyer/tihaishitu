CREATE TABLE learner_account (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP NULL,
    CONSTRAINT uk_learner_username UNIQUE (username)
);

CREATE TABLE learner_session (
    id CHAR(36) PRIMARY KEY,
    learner_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_learner_session_token UNIQUE (token_hash),
    CONSTRAINT fk_learner_session_account FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE
);

CREATE INDEX idx_learner_session_lookup
    ON learner_session(token_hash, expires_at, revoked_at);

CREATE TABLE learner_study_profile (
    learner_id CHAR(36) PRIMARY KEY,
    pace VARCHAR(16) NOT NULL DEFAULT 'normal',
    difficulty VARCHAR(16) NOT NULL DEFAULT 'standard',
    focus_mode VARCHAR(16) NOT NULL DEFAULT 'auto',
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_study_profile_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE
);

CREATE TABLE learner_selected_book (
    learner_id CHAR(36) NOT NULL,
    bank_id CHAR(36) NOT NULL,
    weight_value INT NOT NULL DEFAULT 100,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (learner_id, bank_id),
    CONSTRAINT fk_selected_book_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_selected_book_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE
);

CREATE TABLE learner_focus_knowledge (
    learner_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (learner_id, knowledge_point_id),
    CONSTRAINT fk_focus_knowledge_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_focus_knowledge_point FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id) ON DELETE CASCADE
);

CREATE TABLE learner_world_state (
    learner_id CHAR(36) NOT NULL,
    world_id VARCHAR(64) NOT NULL,
    state_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (learner_id, world_id),
    CONSTRAINT fk_world_state_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE
);

ALTER TABLE study_attempt MODIFY game_id CHAR(36) NULL;
ALTER TABLE study_attempt ADD COLUMN learner_id CHAR(36) NULL;
ALTER TABLE study_attempt ADD COLUMN world_id VARCHAR(64) NULL;
ALTER TABLE study_attempt ADD CONSTRAINT fk_attempt_learner FOREIGN KEY (learner_id)
    REFERENCES learner_account(id) ON DELETE CASCADE;
CREATE INDEX idx_attempt_learner_world ON study_attempt(learner_id, world_id, created_at);

ALTER TABLE answer_record MODIFY game_id CHAR(36) NULL;
ALTER TABLE answer_record ADD COLUMN learner_id CHAR(36) NULL;
ALTER TABLE answer_record ADD COLUMN world_id VARCHAR(64) NULL;
ALTER TABLE answer_record ADD CONSTRAINT fk_answer_learner FOREIGN KEY (learner_id)
    REFERENCES learner_account(id) ON DELETE CASCADE;
CREATE INDEX idx_answer_learner_world_question
    ON answer_record(learner_id, world_id, question_id, created_at);

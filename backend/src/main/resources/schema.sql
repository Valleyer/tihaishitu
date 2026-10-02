CREATE TABLE IF NOT EXISTS question_bank (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    enabled BOOLEAN NOT NULL,
    weight_value INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_point (
    bank_id CHAR(36) NOT NULL,
    id CHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    subject_name VARCHAR(120) NOT NULL,
    category_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    explanation LONGTEXT NOT NULL,
    parent_id CHAR(36),
    prerequisites_json TEXT NOT NULL,
    tags_json TEXT NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (bank_id, id),
    CONSTRAINT fk_knowledge_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS question_item (
    bank_id CHAR(36) NOT NULL,
    id CHAR(36) NOT NULL,
    subject_name VARCHAR(120) NOT NULL,
    category_name VARCHAR(160) NOT NULL,
    chapter_name VARCHAR(200) NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    question_text LONGTEXT NOT NULL,
    answer_json LONGTEXT NOT NULL,
    explanation LONGTEXT NOT NULL,
    aliases_json TEXT NOT NULL,
    keywords_json TEXT NOT NULL,
    tags_json TEXT NOT NULL,
    difficulty INT NOT NULL,
    frequency_value INT NOT NULL,
    enabled BOOLEAN NOT NULL,
    sort_order INT NOT NULL,
    INDEX idx_question_enabled (bank_id, enabled, difficulty),
    PRIMARY KEY (bank_id, id),
    CONSTRAINT fk_question_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS question_option (
    bank_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    option_key VARCHAR(8) NOT NULL,
    option_text LONGTEXT NOT NULL,
    sort_order INT NOT NULL,
    PRIMARY KEY (bank_id, question_id, option_key),
    CONSTRAINT fk_option_question FOREIGN KEY (bank_id, question_id)
        REFERENCES question_item(bank_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS question_knowledge_point (
    bank_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    INDEX idx_question_knowledge (bank_id, knowledge_point_id),
    PRIMARY KEY (bank_id, question_id, knowledge_point_id),
    CONSTRAINT fk_link_question FOREIGN KEY (bank_id, question_id)
        REFERENCES question_item(bank_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_link_knowledge FOREIGN KEY (bank_id, knowledge_point_id)
        REFERENCES knowledge_point(bank_id, id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS game_save (
    id CHAR(36) PRIMARY KEY,
    player_name VARCHAR(60) NOT NULL,
    player_title VARCHAR(120) NOT NULL,
    answer_total INT NOT NULL DEFAULT 0,
    payload_json LONGTEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_game_updated (updated_at)
);

CREATE TABLE IF NOT EXISTS study_attempt (
    id CHAR(36) PRIMARY KEY,
    game_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    question_snapshot_json LONGTEXT NOT NULL,
    standard_answer_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    answered_at TIMESTAMP NULL,
    INDEX idx_attempt_game (game_id, created_at),
    CONSTRAINT fk_attempt_game FOREIGN KEY (game_id)
        REFERENCES game_save(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS answer_record (
    id CHAR(36) PRIMARY KEY,
    game_id CHAR(36) NOT NULL,
    attempt_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    submitted_answer_json LONGTEXT NOT NULL,
    correct BOOLEAN NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_answer_attempt (attempt_id),
    INDEX idx_answer_game_question (game_id, question_id),
    CONSTRAINT fk_answer_game FOREIGN KEY (game_id)
        REFERENCES game_save(id) ON DELETE CASCADE,
    CONSTRAINT fk_answer_attempt FOREIGN KEY (attempt_id)
        REFERENCES study_attempt(id) ON DELETE CASCADE
);

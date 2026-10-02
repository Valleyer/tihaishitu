CREATE TABLE IF NOT EXISTS global_knowledge_point (
    id CHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    subject_name VARCHAR(120) NOT NULL,
    section_name VARCHAR(160) NOT NULL,
    chapter_name VARCHAR(200) NOT NULL,
    default_role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    description LONGTEXT NOT NULL,
    explanation LONGTEXT NOT NULL,
    introduced_version VARCHAR(64),
    merged_into_id CHAR(36),
    sort_order INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_global_knowledge_code UNIQUE (code),
    CONSTRAINT fk_global_knowledge_merge FOREIGN KEY (merged_into_id)
        REFERENCES global_knowledge_point(id)
);

CREATE INDEX idx_global_knowledge_filter
    ON global_knowledge_point(subject_name, section_name, chapter_name, status);

CREATE TABLE IF NOT EXISTS knowledge_alias (
    id CHAR(36) PRIMARY KEY,
    knowledge_point_id CHAR(36) NOT NULL,
    alias VARCHAR(200) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_knowledge_alias UNIQUE (knowledge_point_id, alias),
    CONSTRAINT fk_alias_knowledge FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_alias_text ON knowledge_alias(alias);

CREATE TABLE IF NOT EXISTS question_resource (
    id CHAR(36) PRIMARY KEY,
    subject_name VARCHAR(120) NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_name VARCHAR(240),
    exam_year INT,
    question_number VARCHAR(40),
    question_type VARCHAR(32) NOT NULL,
    presentation_type VARCHAR(32) NOT NULL,
    grading_mode VARCHAR(32) NOT NULL,
    content_markdown LONGTEXT NOT NULL,
    standard_answer_json LONGTEXT NOT NULL,
    analysis_markdown LONGTEXT NOT NULL,
    difficulty INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    parent_question_id CHAR(36),
    derivation_type VARCHAR(32),
    created_by CHAR(36),
    updated_by CHAR(36),
    reviewed_by CHAR(36),
    reviewed_at TIMESTAMP NULL,
    review_comment TEXT,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_question_parent FOREIGN KEY (parent_question_id)
        REFERENCES question_resource(id)
);

CREATE INDEX idx_question_resource_filter
    ON question_resource(subject_name, source_type, exam_year, question_type, grading_mode, status);

CREATE TABLE IF NOT EXISTS question_resource_option (
    id CHAR(36) PRIMARY KEY,
    question_id CHAR(36) NOT NULL,
    option_key VARCHAR(16) NOT NULL,
    option_text LONGTEXT NOT NULL,
    correct_option BOOLEAN NOT NULL,
    sort_order INT NOT NULL,
    CONSTRAINT uk_resource_option UNIQUE (question_id, option_key),
    CONSTRAINT fk_resource_option_question FOREIGN KEY (question_id)
        REFERENCES question_resource(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS question_resource_knowledge (
    question_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    relation_role VARCHAR(16) NOT NULL,
    sort_order INT NOT NULL,
    created_by CHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (question_id, knowledge_point_id),
    CONSTRAINT fk_resource_knowledge_question FOREIGN KEY (question_id)
        REFERENCES question_resource(id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_knowledge_point FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id)
);

CREATE INDEX idx_resource_knowledge_point
    ON question_resource_knowledge(knowledge_point_id, relation_role);

CREATE TABLE IF NOT EXISTS question_bank_item (
    bank_id CHAR(36) NOT NULL,
    question_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bank_id, question_id),
    CONSTRAINT fk_bank_item_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE,
    CONSTRAINT fk_bank_item_question FOREIGN KEY (question_id)
        REFERENCES question_resource(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS app_user (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS app_user_role (
    user_id CHAR(36) NOT NULL,
    role_name VARCHAR(24) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role_name),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id)
        REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS content_audit_log (
    id CHAR(36) PRIMARY KEY,
    actor_user_id CHAR(36),
    action_name VARCHAR(80) NOT NULL,
    entity_type VARCHAR(60) NOT NULL,
    entity_id CHAR(36) NOT NULL,
    metadata_json LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id)
        REFERENCES app_user(id)
);

CREATE INDEX idx_audit_entity ON content_audit_log(entity_type, entity_id, created_at);


CREATE TABLE IF NOT EXISTS legacy_knowledge_map (
    bank_id CHAR(36) NOT NULL,
    legacy_id CHAR(36) NOT NULL,
    global_id CHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bank_id, legacy_id),
    CONSTRAINT fk_legacy_knowledge_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE,
    CONSTRAINT fk_legacy_knowledge_global FOREIGN KEY (global_id)
        REFERENCES global_knowledge_point(id)
);

CREATE TABLE IF NOT EXISTS legacy_question_map (
    bank_id CHAR(36) NOT NULL,
    legacy_id CHAR(36) NOT NULL,
    global_id CHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bank_id, legacy_id),
    CONSTRAINT uk_legacy_question_global UNIQUE (global_id),
    CONSTRAINT fk_legacy_question_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE,
    CONSTRAINT fk_legacy_question_global FOREIGN KEY (global_id)
        REFERENCES question_resource(id) ON DELETE CASCADE
);

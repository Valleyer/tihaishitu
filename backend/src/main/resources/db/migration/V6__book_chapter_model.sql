CREATE TABLE IF NOT EXISTS question_bank_chapter (
    id CHAR(36) PRIMARY KEY,
    bank_id CHAR(36) NOT NULL,
    parent_id CHAR(36),
    chapter_code VARCHAR(64) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    sort_order INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bank_chapter_code UNIQUE (bank_id, chapter_code),
    CONSTRAINT uk_bank_chapter_identity UNIQUE (bank_id, id),
    CONSTRAINT fk_bank_chapter_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE,
    CONSTRAINT fk_bank_chapter_parent FOREIGN KEY (bank_id, parent_id)
        REFERENCES question_bank_chapter(bank_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_bank_chapter_parent
    ON question_bank_chapter(bank_id, parent_id, sort_order);

ALTER TABLE question_bank_item ADD COLUMN chapter_id CHAR(36);

CREATE INDEX idx_bank_item_chapter
    ON question_bank_item(bank_id, chapter_id, sort_order);

ALTER TABLE question_bank_item
    ADD CONSTRAINT fk_bank_item_chapter FOREIGN KEY (bank_id, chapter_id)
        REFERENCES question_bank_chapter(bank_id, id) ON DELETE CASCADE;

CREATE TABLE IF NOT EXISTS question_bank_knowledge (
    bank_id CHAR(36) NOT NULL,
    knowledge_point_id CHAR(36) NOT NULL,
    chapter_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bank_id, knowledge_point_id),
    CONSTRAINT fk_bank_knowledge_bank FOREIGN KEY (bank_id)
        REFERENCES question_bank(id) ON DELETE CASCADE,
    CONSTRAINT fk_bank_knowledge_point FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id),
    CONSTRAINT fk_bank_knowledge_chapter FOREIGN KEY (bank_id, chapter_id)
        REFERENCES question_bank_chapter(bank_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_bank_knowledge_point
    ON question_bank_knowledge(knowledge_point_id, bank_id);

CREATE INDEX idx_bank_knowledge_chapter
    ON question_bank_knowledge(bank_id, chapter_id, sort_order);

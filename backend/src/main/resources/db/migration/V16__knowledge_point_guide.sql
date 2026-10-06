CREATE TABLE knowledge_point_guide (
    knowledge_point_id CHAR(36) PRIMARY KEY,
    content_markdown LONGTEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_by CHAR(36) NULL,
    updated_by CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_knowledge_guide_point FOREIGN KEY (knowledge_point_id)
        REFERENCES global_knowledge_point(id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_guide_creator FOREIGN KEY (created_by)
        REFERENCES learner_account(id),
    CONSTRAINT fk_knowledge_guide_updater FOREIGN KEY (updated_by)
        REFERENCES learner_account(id)
);


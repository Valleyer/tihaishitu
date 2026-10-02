CREATE TABLE IF NOT EXISTS knowledge_merge_history (
    id CHAR(36) PRIMARY KEY,
    source_knowledge_id CHAR(36) NOT NULL,
    target_knowledge_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36),
    migrated_relation_count INT NOT NULL,
    collapsed_relation_count INT NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_merge_source FOREIGN KEY (source_knowledge_id)
        REFERENCES global_knowledge_point(id),
    CONSTRAINT fk_knowledge_merge_target FOREIGN KEY (target_knowledge_id)
        REFERENCES global_knowledge_point(id),
    CONSTRAINT fk_knowledge_merge_actor FOREIGN KEY (actor_user_id)
        REFERENCES app_user(id)
);

CREATE INDEX idx_knowledge_merge_source
    ON knowledge_merge_history(source_knowledge_id, created_at);

CREATE INDEX idx_knowledge_merge_target
    ON knowledge_merge_history(target_knowledge_id, created_at);

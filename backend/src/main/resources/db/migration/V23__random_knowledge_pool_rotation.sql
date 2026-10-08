-- PR6：按 Learner 持久化 RANDOM 第一层 KnowledgePoint 池的跨日轮换状态。
-- last_requested_pool 记录“本次轮到的池”，即使 wrong 池为空而回退 all，也仍写 wrong。
-- 旧 Learner 不回填；没有记录时第一次新规则触发固定从 all 开始。
CREATE TABLE learner_random_kp_rotation (
    learner_id CHAR(36) PRIMARY KEY,
    last_requested_pool VARCHAR(16) NOT NULL,
    selection_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_random_kp_rotation_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE
);

-- PR6 合并前修复：为每个 Learner 保存明确的最近 RANDOM Attempt。
-- 不能用秒级 created_at 并列后的随机 UUID 字典序推断真实发题顺序。
-- 不回填旧 Attempt；升级后的每次现代 RANDOM 发题都在同一事务内更新此指针。
CREATE TABLE learner_random_attempt_cursor (
    learner_id CHAR(36) PRIMARY KEY,
    last_random_attempt_id CHAR(36) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_random_attempt_cursor_learner FOREIGN KEY (learner_id)
        REFERENCES learner_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_random_attempt_cursor_attempt FOREIGN KEY (last_random_attempt_id)
        REFERENCES study_attempt(id) ON DELETE CASCADE
);

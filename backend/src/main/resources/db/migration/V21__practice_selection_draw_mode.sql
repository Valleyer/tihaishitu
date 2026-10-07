-- PR3 Practice Selection V2：正式 Attempt 明确记录“这一题是怎么被选出来的”。
--
-- 长期规则：不再靠 world_id / practice_session.intent 反推历史来源。
--   draw_mode   random | chapter | knowledge | wrong | legacy
--   draw_reason oldest | wrong | wrong_fallback（当前只由 RANDOM 使用，其他模式允许 NULL）
--
-- 旧 Attempt 保持 draw_mode = NULL，不做高风险历史回填：
-- “最近一次 graded”仍然读取 Learner 全部历史正式 Attempt，不只看 V21 之后的数据。
-- RANDOM 的每日硬去重事实来源是 (learner_id, draw_mode='random', created_at)，
-- 因此索引必须包含这三列；MySQL 5.7 兼容，不使用任何 MySQL 8 专属语法。

-- 每列一条 ALTER，MySQL 5.7 与 H2(MySQL mode) 都接受；不写显式 NULL（两列默认可空）。
ALTER TABLE study_attempt ADD COLUMN draw_mode VARCHAR(24);
ALTER TABLE study_attempt ADD COLUMN draw_reason VARCHAR(24);

CREATE INDEX idx_attempt_learner_draw_mode
    ON study_attempt(learner_id, draw_mode, created_at, question_id);

-- =============================================================================
-- 万境书院 · 上线前数据库只读审计（pre-deploy DB audit）
--
-- 用途：在 mysqldump 之前，对本地库做一次完整、可人工比对的只读体检，
--       并把结果与导入生产后的 scripts/verify-prod-db.sql 逐项对比。
--
-- 安全约束（必须遵守）：
--   * 本文件只包含 SELECT / SHOW，不包含任何 DELETE / DROP / UPDATE / ALTER / INSERT；
--   * 不修改任何表、任何行、任何账号；
--   * 不清理 legacy 表，不清理 smoke-* 测试账号。
--
-- 执行方式（示例，不要把真实密码写进仓库）：
--   mysql -h 127.0.0.1 -P 3306 -u <LOCAL_USER> -p \
--     --default-character-set=utf8mb4 tihaishitu \
--     < scripts/predeploy-db-audit.sql > predeploy-audit-$(date +%F).txt
--
-- 兼容性：MySQL 5.7。不使用窗口函数、CTE、JSON_TABLE 等 MySQL 8 专属语法。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 0. 会话与版本信息
-- -----------------------------------------------------------------------------
SELECT '0.1 server & database' AS section, VERSION() AS mysql_version,
       @@version_comment AS version_comment, @@hostname AS host_name,
       @@port AS port, DATABASE() AS current_database, CURRENT_USER() AS current_user,
       NOW() AS audited_at, @@time_zone AS session_time_zone, @@system_time_zone AS system_time_zone;

SELECT '0.2 default charset' AS section, @@character_set_server AS server_charset,
       @@collation_server AS server_collation,
       @@character_set_database AS database_charset,
       @@collation_database AS database_collation,
       @@sql_mode AS sql_mode, @@lower_case_table_names AS lower_case_table_names;

SHOW VARIABLES LIKE 'innodb\_file\_per\_table';

SELECT '0.4 flyway engine expectations' AS section,
       @@innodb_version AS innodb_version, @@max_connections AS max_connections;


-- -----------------------------------------------------------------------------
-- 1. 全部表（含引擎、估算行数、字符集）
--    TABLE_ROWS 是 InnoDB 估算值，用于快速体检；精确行数见第 4 / 5 节。
-- -----------------------------------------------------------------------------
SELECT TABLE_NAME, ENGINE, TABLE_ROWS AS estimated_rows, TABLE_COLLATION, TABLE_COMMENT
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
 ORDER BY TABLE_NAME;

SELECT '1.2 table count' AS section, COUNT(*) AS total_tables,
       SUM(CASE WHEN ENGINE = 'InnoDB' THEN 1 ELSE 0 END) AS innodb_tables
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';


-- -----------------------------------------------------------------------------
-- 2. Flyway 状态
--    完整迁移必须把 flyway_schema_history 一起带到生产库，
--    否则生产库已有 V1–V18 的表，但 Flyway 认为一个都没执行过。
-- -----------------------------------------------------------------------------
SELECT installed_rank, version, description, type, script, checksum,
       installed_by, installed_on, execution_time, success
  FROM flyway_schema_history
 ORDER BY installed_rank;

SELECT '2.2 migration summary' AS section,
       COUNT(*) AS total_migrations,
       SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS succeeded,
       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed,
       MIN(version) AS min_version, MAX(version) AS max_version
  FROM flyway_schema_history;

-- 期望结果：0 行。任何一行都必须在导入生产前查清原因。
SELECT installed_rank, version, description, script, installed_on
  FROM flyway_schema_history
 WHERE success <> 1
 ORDER BY installed_rank;

-- 期望结果：latest_version = 18（V13 是 Java/JDBC migration，同样算 V13）。
SELECT version AS latest_version, description AS latest_description, type AS latest_type,
       script AS latest_script, success AS latest_success
  FROM flyway_schema_history
 ORDER BY installed_rank DESC
 LIMIT 1;


-- -----------------------------------------------------------------------------
-- 3. 核心表存在性（缺失即视为异常，导入后需与 verify-prod-db.sql 一致）
-- -----------------------------------------------------------------------------
SELECT expected.table_name,
       CASE WHEN t.TABLE_NAME IS NULL THEN 'MISSING' ELSE 'OK' END AS presence,
       t.ENGINE, t.TABLE_COLLATION
  FROM (
        SELECT 'question_bank' AS table_name UNION ALL
        SELECT 'question_bank_chapter' UNION ALL
        SELECT 'question_bank_knowledge' UNION ALL
        SELECT 'global_knowledge_point' UNION ALL
        SELECT 'question_resource' UNION ALL
        SELECT 'question_resource_option' UNION ALL
        SELECT 'question_resource_knowledge' UNION ALL
        SELECT 'learner_account' UNION ALL
        SELECT 'learner_account_role' UNION ALL
        SELECT 'learner_world_state' UNION ALL
        SELECT 'learner_practice_session' UNION ALL
        SELECT 'study_attempt' UNION ALL
        SELECT 'answer_record' UNION ALL
        SELECT 'learner_question_mastery' UNION ALL
        SELECT 'learner_wrong_question' UNION ALL
        SELECT 'knowledge_point_guide' UNION ALL
        -- legacy / compatibility：仍然被运行时代码或启动迁移器使用，本轮不删除
        SELECT 'knowledge_point' UNION ALL
        SELECT 'question_item' UNION ALL
        SELECT 'question_option' UNION ALL
        SELECT 'question_knowledge_point' UNION ALL
        SELECT 'legacy_knowledge_map' UNION ALL
        SELECT 'legacy_question_map' UNION ALL
        SELECT 'question_bank_item' UNION ALL
        SELECT 'game_save' UNION ALL
        SELECT 'app_user' UNION ALL
        SELECT 'app_user_role'
       ) AS expected
  LEFT JOIN information_schema.TABLES AS t
         ON t.TABLE_SCHEMA = DATABASE()
        AND t.TABLE_NAME = expected.table_name
 ORDER BY presence DESC, expected.table_name;


-- -----------------------------------------------------------------------------
-- 4. 核心表精确行数（mysqldump 前后人工比对）
--    这些数字是“本次快照”，不要写进代码断言；verify-prod-db.sql 只做结构与非空校验。
-- -----------------------------------------------------------------------------
SELECT '4 core tables row counts' AS section,
       (SELECT COUNT(*) FROM question_bank)                AS question_bank,
       (SELECT COUNT(*) FROM question_bank_chapter)        AS question_bank_chapter,
       (SELECT COUNT(*) FROM question_bank_knowledge)      AS question_bank_knowledge,
       (SELECT COUNT(*) FROM global_knowledge_point)       AS global_knowledge_point,
       (SELECT COUNT(*) FROM question_resource)            AS question_resource,
       (SELECT COUNT(*) FROM question_resource_option)     AS question_resource_option,
       (SELECT COUNT(*) FROM question_resource_knowledge)  AS question_resource_knowledge;

SELECT '4.2 learner & practice row counts' AS section,
       (SELECT COUNT(*) FROM learner_account)              AS learner_account,
       (SELECT COUNT(*) FROM learner_account_role)         AS learner_account_role,
       (SELECT COUNT(*) FROM learner_world_state)          AS learner_world_state,
       (SELECT COUNT(*) FROM learner_practice_session)     AS learner_practice_session,
       (SELECT COUNT(*) FROM study_attempt)                AS study_attempt,
       (SELECT COUNT(*) FROM answer_record)                AS answer_record;

SELECT '4.3 mastery / wrong book / guide row counts' AS section,
       (SELECT COUNT(*) FROM learner_question_mastery)     AS learner_question_mastery,
       (SELECT COUNT(*) FROM learner_wrong_question)       AS learner_wrong_question,
       (SELECT COUNT(*) FROM knowledge_point_guide)        AS knowledge_point_guide;

-- 非异常判断：这些计数为 0 时说明库是空库或 seed-only 状态，不应直接上生产。
SELECT '4.4 non-empty sanity check' AS section,
       CASE WHEN (SELECT COUNT(*) FROM question_resource) = 0 THEN 'EMPTY_QUESTION_POOL' ELSE 'OK' END AS question_resource_state,
       CASE WHEN (SELECT COUNT(*) FROM global_knowledge_point) = 0 THEN 'EMPTY_KNOWLEDGE' ELSE 'OK' END AS knowledge_state,
       CASE WHEN (SELECT COUNT(*) FROM learner_account) = 0 THEN 'EMPTY_LEARNER' ELSE 'OK' END AS learner_state;


-- -----------------------------------------------------------------------------
-- 5. legacy / compatibility 表行数（本轮只审计，不清理）
--    这些表仍有运行时代码依赖，直接 DROP 会导致应用启动或读取报错。
--    背景见 docs/deployment.md 的“数据库迁移与 legacy 表”一节。
-- -----------------------------------------------------------------------------
SELECT '5 legacy tables row counts' AS section,
       (SELECT COUNT(*) FROM knowledge_point)             AS knowledge_point,
       (SELECT COUNT(*) FROM question_item)               AS question_item,
       (SELECT COUNT(*) FROM question_option)             AS question_option,
       (SELECT COUNT(*) FROM question_knowledge_point)    AS question_knowledge_point,
       (SELECT COUNT(*) FROM legacy_knowledge_map)        AS legacy_knowledge_map,
       (SELECT COUNT(*) FROM legacy_question_map)         AS legacy_question_map,
       (SELECT COUNT(*) FROM question_bank_item)          AS question_bank_item,
       (SELECT COUNT(*) FROM game_save)                   AS game_save,
       (SELECT COUNT(*) FROM app_user)                    AS app_user,
       (SELECT COUNT(*) FROM app_user_role)               AS app_user_role;

-- 官方《数学一》Book（UUID 固定，改名不换 UUID、不新建 Book）。
SELECT id, name, description, enabled, weight_value, revision
  FROM question_bank
 WHERE id = '628a3d64-c820-4d1f-b482-8e2715bb7cf2';

-- 品牌文案核对：期望描述为“万境书院官方维护的一站式数学一学习书籍。”。
-- 若仍是“题海仕途…”或“知境…”，应用启动时 OfficialMath1BookBootstrap 会幂等升级并 revision + 1。
SELECT id, name, description, revision
  FROM question_bank
 WHERE description LIKE '%官方维护的一站式数学一学习书籍%';


-- -----------------------------------------------------------------------------
-- 6. smoke 测试账号（本轮不自动删除）
--    上线前如需清理：先备份 → 用户明确批准 → 再单独事务清理。
-- -----------------------------------------------------------------------------
SELECT id, username, display_name, status, created_at
  FROM learner_account
 WHERE username LIKE 'smoke%'
 ORDER BY username;

-- 所有学习者账号状态分布，便于判断是否存在遗留测试账号。
SELECT status, COUNT(*) AS account_count
  FROM learner_account
 GROUP BY status
 ORDER BY status;


-- -----------------------------------------------------------------------------
-- 7. 外键与索引体检（导入前确认约束完整，避免导入后 FK 报错）
-- -----------------------------------------------------------------------------
SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
  FROM information_schema.KEY_COLUMN_USAGE
 WHERE TABLE_SCHEMA = DATABASE()
   AND REFERENCED_TABLE_NAME IS NOT NULL
 ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;

SELECT '7.2 foreign key count' AS section, COUNT(*) AS foreign_keys
  FROM information_schema.TABLE_CONSTRAINTS
 WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY';

SELECT '7.3 non-primary index count' AS section, COUNT(DISTINCT TABLE_NAME, INDEX_NAME) AS indexes
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME <> 'PRIMARY';


-- -----------------------------------------------------------------------------
-- 8. 审计结论提示（人工判读，不写死业务数量）
-- -----------------------------------------------------------------------------
SELECT '8 audit checklist' AS section,
       (SELECT CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'CHECK_FAILED_MIGRATIONS' END
          FROM flyway_schema_history WHERE success <> 1)                       AS flyway_failures,
       (SELECT CASE WHEN MAX(CAST(version AS UNSIGNED)) = 18 THEN 'OK_V18' ELSE 'UNEXPECTED_MAX_VERSION' END
          FROM flyway_schema_history WHERE success = 1 AND version REGEXP '^[0-9]+$') AS latest_migration,
       (SELECT CASE WHEN COUNT(*) > 0 THEN 'OK' ELSE 'MISSING_CORE_TABLE' END
          FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME IN ('global_knowledge_point','question_resource','learner_account','question_bank')) AS core_tables,
       (SELECT CASE WHEN COUNT(*) > 0 THEN 'PRESENT_REVIEW_BEFORE_CLEANUP' ELSE 'ABSENT' END
          FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME IN ('knowledge_point','question_item','question_option','question_knowledge_point',
                              'legacy_knowledge_map','legacy_question_map','question_bank_item',
                              'game_save','app_user','app_user_role')) AS legacy_tables;

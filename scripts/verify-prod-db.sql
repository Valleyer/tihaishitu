-- =============================================================================
-- 万境求知 · 导入生产库后的只读验证（post-import verification）
--
-- 用途：mysqldump 导入服务器 MySQL 5.7 之后、启动生产 Spring Boot 之前运行。
--       确认迁移完整、核心数据已迁入，且不是意外空库 / seed-only 状态。
--
-- 安全约束（必须遵守）：
--   * 本文件只包含 SELECT / SHOW，不包含任何 DELETE / DROP / UPDATE / ALTER / INSERT；
--   * 不创建、不修改任何表与数据。
--
-- 执行方式（示例，不要把真实密码写进仓库）：
--   mysql -h 127.0.0.1 -P 3306 -u <PROD_USER> -p \
--     --default-character-set=utf8mb4 tihaishitu \
--     < scripts/verify-prod-db.sql > verify-prod-$(date +%F).txt
--
-- 比对方式：把本文件第 3 节输出的行数，与 scripts/predeploy-db-audit.sql
--           第 4 / 5 节在本地的输出逐项对照。两边数字不一致时先查清原因，
--           再启动 Spring Boot。
--
-- 兼容性：MySQL 5.7。不使用窗口函数、CTE、JSON_TABLE 等 MySQL 8 专属语法。
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 0. 目标库身份确认（先确认连对了库，再谈数据）
-- -----------------------------------------------------------------------------
SELECT '0.1 target database' AS section, VERSION() AS mysql_version,
       DATABASE() AS current_database, CURRENT_USER() AS current_user,
       NOW() AS verified_at, @@character_set_database AS database_charset,
       @@collation_database AS database_collation;

SELECT '0.2 expected identity' AS section,
       CASE WHEN DATABASE() = 'tihaishitu' THEN 'OK_SCHEMA_TIHAISHITU' ELSE 'UNEXPECTED_SCHEMA' END AS schema_check,
       CASE WHEN VERSION() LIKE '5.7%' THEN 'OK_MYSQL_5_7' ELSE 'CHECK_SERVER_VERSION' END AS version_check;


-- -----------------------------------------------------------------------------
-- 1. Flyway：全部 success = 1，且最新为 V18（V13 是 Java/JDBC migration）
-- -----------------------------------------------------------------------------
SELECT installed_rank, version, description, type, script, installed_on, success
  FROM flyway_schema_history
 ORDER BY installed_rank;

SELECT '1.2 migration summary' AS section,
       COUNT(*) AS total_migrations,
       SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS succeeded,
       SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END) AS failed
  FROM flyway_schema_history;

-- 期望 0 行：任何失败 migration 都必须先处理，不能启动生产后端。
SELECT installed_rank, version, description, script, installed_on
  FROM flyway_schema_history
 WHERE success <> 1
 ORDER BY installed_rank;

-- 期望 latest_version = 18。
SELECT version AS latest_version, description AS latest_description, type AS latest_type,
       success AS latest_success
  FROM flyway_schema_history
 ORDER BY installed_rank DESC
 LIMIT 1;

-- 期望 OK：V1–V18 共 18 条全部 success（含 Java V13）。
SELECT '1.5 expected_v1_to_v18' AS section,
       CASE WHEN COUNT(*) = 18
             AND SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) = 18
             AND MAX(CASE WHEN version REGEXP '^[0-9]+$' THEN CAST(version AS UNSIGNED) END) = 18
            THEN 'OK_V1_TO_V18' ELSE 'MISMATCH' END AS flyway_state
  FROM flyway_schema_history;


-- -----------------------------------------------------------------------------
-- 2. 核心表存在性 + 精确行数（与本地 predeploy 审计对照）
-- -----------------------------------------------------------------------------
SELECT expected.table_name,
       CASE WHEN t.TABLE_NAME IS NULL THEN 'MISSING' ELSE 'OK' END AS presence,
       t.ENGINE, t.TABLE_COLLATION,
       (SELECT COUNT(*) FROM information_schema.COLUMNS c
         WHERE c.TABLE_SCHEMA = DATABASE() AND c.TABLE_NAME = expected.table_name) AS column_count
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
        -- legacy / compatibility：同样必须一起迁移，本轮不删除
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
-- 3. 生产库行数快照（人工与本地 predeploy 审计逐项比对）
-- -----------------------------------------------------------------------------
SELECT '3.1 question & knowledge' AS section,
       (SELECT COUNT(*) FROM question_bank)                AS question_bank,
       (SELECT COUNT(*) FROM question_bank_chapter)        AS question_bank_chapter,
       (SELECT COUNT(*) FROM question_bank_knowledge)      AS question_bank_knowledge,
       (SELECT COUNT(*) FROM global_knowledge_point)       AS global_knowledge_point,
       (SELECT COUNT(*) FROM question_resource)            AS question_resource,
       (SELECT COUNT(*) FROM question_resource_option)     AS question_resource_option,
       (SELECT COUNT(*) FROM question_resource_knowledge)  AS question_resource_knowledge;

SELECT '3.2 learner & practice' AS section,
       (SELECT COUNT(*) FROM learner_account)              AS learner_account,
       (SELECT COUNT(*) FROM learner_account_role)         AS learner_account_role,
       (SELECT COUNT(*) FROM learner_world_state)          AS learner_world_state,
       (SELECT COUNT(*) FROM learner_practice_session)     AS learner_practice_session,
       (SELECT COUNT(*) FROM study_attempt)                AS study_attempt,
       (SELECT COUNT(*) FROM answer_record)                AS answer_record;

SELECT '3.3 mastery / wrong book / guide' AS section,
       (SELECT COUNT(*) FROM learner_question_mastery)     AS learner_question_mastery,
       (SELECT COUNT(*) FROM learner_wrong_question)       AS learner_wrong_question,
       (SELECT COUNT(*) FROM knowledge_point_guide)        AS knowledge_point_guide;

SELECT '3.4 legacy / compatibility' AS section,
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


-- -----------------------------------------------------------------------------
-- 4. 非空 / 非 seed-only 校验
--    判断依据是“结构 + 数量级”，不硬编码业务数量：
--    question_resource 与 global_knowledge_point 明显低于本地快照即为异常。
-- -----------------------------------------------------------------------------
SELECT '4.1 core data presence' AS section,
       CASE WHEN (SELECT COUNT(*) FROM question_resource) > 0 THEN 'OK' ELSE 'EMPTY_QUESTION_RESOURCE' END       AS question_resource_state,
       CASE WHEN (SELECT COUNT(*) FROM global_knowledge_point) > 0 THEN 'OK' ELSE 'EMPTY_KNOWLEDGE' END          AS knowledge_state,
       CASE WHEN (SELECT COUNT(*) FROM question_bank_knowledge) > 0 THEN 'OK' ELSE 'EMPTY_BOOK_KNOWLEDGE' END    AS book_knowledge_state,
       CASE WHEN (SELECT COUNT(*) FROM question_resource_knowledge) > 0 THEN 'OK' ELSE 'EMPTY_RELATIONS' END     AS relation_state;

SELECT '4.2 learner data migrated' AS section,
       CASE WHEN (SELECT COUNT(*) FROM learner_account) > 0 THEN 'OK_HAS_LEARNERS' ELSE 'NO_LEARNER_DATA' END    AS learner_state,
       CASE WHEN (SELECT COUNT(*) FROM learner_account_role) >= 0 THEN 'OK' ELSE 'CHECK' END                     AS learner_role_state,
       CASE WHEN (SELECT COUNT(*) FROM study_attempt) > 0 THEN 'OK_HAS_ATTEMPTS' ELSE 'NO_ATTEMPT_DATA' END      AS attempt_state,
       CASE WHEN (SELECT COUNT(*) FROM answer_record) > 0 THEN 'OK_HAS_ANSWERS' ELSE 'NO_ANSWER_DATA' END        AS answer_state;

-- seed-only 特征：只有 Book 骨架（章节存在），但题库与知识点关系几乎为空。
SELECT '4.3 seed_only_detection' AS section,
       CASE WHEN (SELECT COUNT(*) FROM question_bank_chapter) > 0
             AND (SELECT COUNT(*) FROM question_resource) = 0
            THEN 'SUSPECT_SEED_ONLY' ELSE 'OK' END AS seed_only_state,
       CASE WHEN (SELECT COUNT(*) FROM flyway_schema_history) = 0
            THEN 'MISSING_FLYWAY_HISTORY' ELSE 'OK' END AS flyway_history_state;


-- -----------------------------------------------------------------------------
-- 5. 官方《数学一》Book 与品牌文案核对
-- -----------------------------------------------------------------------------
SELECT id, name, description, enabled, weight_value, revision
  FROM question_bank
 WHERE id = '628a3d64-c820-4d1f-b482-8e2715bb7cf2';

-- 期望 brand_description = OK_BRAND：应用启动后 OfficialMath1BookBootstrap
-- 会把旧品牌占位说明幂等升级为“万境求知官方维护的一站式数学一学习书籍。”。
-- 首次启动生产后端之前，这里可能仍是旧文案，属于预期状态。
SELECT '5.2 brand description' AS section,
       CASE WHEN EXISTS (SELECT 1 FROM question_bank
                          WHERE id = '628a3d64-c820-4d1f-b482-8e2715bb7cf2'
                            AND description = '万境求知官方维护的一站式数学一学习书籍。')
            THEN 'OK_BRAND' ELSE 'PENDING_BOOTSTRAP_UPGRADE' END AS brand_description;

SELECT '5.3 unexpected legacy brand text' AS section,
       CASE WHEN EXISTS (SELECT 1 FROM question_bank
                          WHERE id = '628a3d64-c820-4d1f-b482-8e2715bb7cf2'
                            AND (description LIKE '知境%' OR description LIKE '题海仕途%'))
            THEN 'LEGACY_BRAND_PRESENT' ELSE 'OK' END AS legacy_brand_state;

SELECT id, name, LEFT(description, 60) AS description_prefix, revision
  FROM question_bank
 ORDER BY name;


-- -----------------------------------------------------------------------------
-- 6. 结构与约束完整性
-- -----------------------------------------------------------------------------
SELECT '6.1 totals' AS section,
       (SELECT COUNT(*) FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE')            AS total_tables,
       (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
         WHERE TABLE_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'FOREIGN KEY')      AS foreign_keys,
       (SELECT COUNT(DISTINCT TABLE_NAME, INDEX_NAME) FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME <> 'PRIMARY')              AS non_primary_indexes;

-- 存在但本地没有的表（导入方式异常时会暴露）
SELECT TABLE_NAME, TABLE_ROWS AS estimated_rows, CREATE_TIME
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
 ORDER BY TABLE_NAME;


-- -----------------------------------------------------------------------------
-- 7. smoke 测试账号（本轮不自动删除；上线前清理需用户明确批准）
-- -----------------------------------------------------------------------------
SELECT id, username, display_name, status, created_at
  FROM learner_account
 WHERE username LIKE 'smoke%'
 ORDER BY username;

SELECT status, COUNT(*) AS account_count
  FROM learner_account
 GROUP BY status
 ORDER BY status;


-- -----------------------------------------------------------------------------
-- 8. 验证结论汇总（人工判读）
-- -----------------------------------------------------------------------------
SELECT '8 verification checklist' AS section,
       (SELECT CASE WHEN COUNT(*) = 0 THEN 'OK' ELSE 'FAILED_MIGRATIONS' END
          FROM flyway_schema_history WHERE success <> 1)                                     AS flyway_failures,
       (SELECT CASE WHEN MAX(CAST(version AS UNSIGNED)) = 18 THEN 'OK_V18' ELSE 'UNEXPECTED_MAX_VERSION' END
          FROM flyway_schema_history WHERE success = 1 AND version REGEXP '^[0-9]+$')        AS latest_migration,
       (SELECT CASE WHEN COUNT(*) = 26 THEN 'OK_ALL_EXPECTED_TABLES' ELSE 'MISSING_EXPECTED_TABLE' END
          FROM information_schema.TABLES
         WHERE TABLE_SCHEMA = DATABASE()
           AND TABLE_NAME IN ('question_bank','question_bank_chapter','question_bank_knowledge',
                              'global_knowledge_point','question_resource','question_resource_option',
                              'question_resource_knowledge','learner_account','learner_account_role',
                              'learner_world_state','learner_practice_session','study_attempt',
                              'answer_record','learner_question_mastery','learner_wrong_question',
                              'knowledge_point_guide','knowledge_point','question_item','question_option',
                              'question_knowledge_point','legacy_knowledge_map','legacy_question_map',
                              'question_bank_item','game_save','app_user','app_user_role'))     AS expected_tables,
       (SELECT CASE WHEN COUNT(*) > 0 THEN 'OK_HAS_QUESTION_DATA' ELSE 'EMPTY_QUESTION_DATA' END
          FROM question_resource)                                                            AS question_data,
       (SELECT CASE WHEN COUNT(*) > 0 THEN 'OK_HAS_LEARNERS' ELSE 'NO_LEARNER_DATA' END
          FROM learner_account)                                                              AS learner_data;

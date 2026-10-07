-- =============================================================================
-- 万境书院：考研真题 metadata 整理脚本（幂等 / MySQL 5.7 兼容）
--
-- 目的：
--   1. 对现有正式父题（parent_question_id IS NULL）回填 exam_year；
--   2. 为 408 来源名补上四位年份前缀，使做题页 examLabel 与来源名一致。
--
-- 长期规则（见 docs/PROJECT_RULES.md）：
--   * 真题年份的事实来源是 question_resource.exam_year，不新增冗余标签表；
--   * 年份不是 KnowledgePoint，不会用来污染 Mastery；
--   * 已有 exam_year 一律不覆盖，只对空值做确定性推断；
--   * 无法确定性推断的一律不猜，由审计 SELECT 输出 unresolved 列表。
--
-- 执行顺序：
--   第 1 段 审计 SELECT（改动前）
--   第 2 段 UPDATE（幂等）
--   第 3 段 验证 SELECT（改动后，重复执行结果不变）
--
-- 本脚本可重复执行：第二次执行时第 2 段的两个 UPDATE 影响行数都应为 0。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 审计：正式父题总数 / 分科目题数 / 年份覆盖情况
-- -----------------------------------------------------------------------------
-- -----------------------------------------------------------------------------
-- 1. 审计：正式父题总数 / 分科目题数 / 年份覆盖情况
--
-- 注意：MySQL 中 `NULL NOT REGEXP '...'` 的结果是 NULL，不会进入 CASE 的 TRUE 分支。
-- 因此凡是“题号没有年份前缀”的判断，都必须显式写成：
--     question_number IS NULL OR question_number NOT REGEXP '^[0-9]{4}-'
-- 审计、UPDATE、verification 三处必须使用同一逻辑。
-- -----------------------------------------------------------------------------
SELECT
    COUNT(*)                                                        AS 正式父题总数,
    SUM(CASE WHEN subject_name = '数学一' THEN 1 ELSE 0 END)          AS 数学一题数,
    SUM(CASE WHEN subject_name LIKE '%408%' THEN 1 ELSE 0 END)        AS cs408题数,
    SUM(CASE WHEN exam_year IS NOT NULL THEN 1 ELSE 0 END)            AS 已有年份,
    SUM(CASE WHEN exam_year IS NULL THEN 1 ELSE 0 END)                AS 缺少年份,
    SUM(CASE WHEN exam_year IS NULL AND question_number REGEXP '^[0-9]{4}-' THEN 1 ELSE 0 END)
                                                                    AS 可由题号推断,
    SUM(CASE WHEN exam_year IS NULL
              AND (question_number IS NULL OR question_number NOT REGEXP '^[0-9]{4}-')
              AND source_name REGEXP '^[0-9]{4}' THEN 1 ELSE 0 END)     AS 可由来源推断,
    SUM(CASE WHEN exam_year IS NULL
              AND (question_number IS NULL OR question_number NOT REGEXP '^[0-9]{4}-')
              AND (source_name IS NULL OR source_name NOT REGEXP '^[0-9]{4}') THEN 1 ELSE 0 END)
                                                                    AS 无法推断
  FROM question_resource
 WHERE parent_question_id IS NULL;

-- -----------------------------------------------------------------------------
-- 2. 审计：来源名尚未带年份前缀的正式父题（含年份分布）
-- -----------------------------------------------------------------------------
SELECT subject_name,
       source_name,
       exam_year,
       COUNT(*) AS 题数,
       SUM(CASE WHEN source_name REGEXP '^[0-9]{4}' THEN 1 ELSE 0 END) AS 已有年份前缀
  FROM question_resource
 WHERE parent_question_id IS NULL
 GROUP BY subject_name, source_name, exam_year
 ORDER BY subject_name, exam_year, source_name;

-- -----------------------------------------------------------------------------
-- 3. UPDATE A：exam_year 回填
--    规则 A：question_number 形如 2014-1 / 2022-3 时取四位年份；
--    规则 B：question_number 没有年份前缀（含 NULL）且 source_name 以四位年份开头时取该年份。
--    只更新 exam_year IS NULL 的行；已有年份一律不覆盖。
-- -----------------------------------------------------------------------------
UPDATE question_resource
   SET exam_year = CAST(LEFT(question_number, 4) AS UNSIGNED),
       updated_at = CURRENT_TIMESTAMP
 WHERE parent_question_id IS NULL
   AND exam_year IS NULL
   AND question_number REGEXP '^[0-9]{4}-';

UPDATE question_resource
   SET exam_year = CAST(LEFT(source_name, 4) AS UNSIGNED),
       updated_at = CURRENT_TIMESTAMP
 WHERE parent_question_id IS NULL
   AND exam_year IS NULL
   AND (question_number IS NULL OR question_number NOT REGEXP '^[0-9]{4}-')
   AND source_name REGEXP '^[0-9]{4}';

-- -----------------------------------------------------------------------------
-- 4. UPDATE B：408 来源名补年份前缀
--    幂等条件（只看“开头是不是这一年”，不看全文）：
--      * 来源名去掉首尾空白后，不以该 exam_year 的四位数字开头。
--    如果来源名中间偶然出现同一个四位数字（页码、正文年份等），也不能被当成
--    “已经加过前缀”，否则会漏补；因此不再使用全文 LIKE '%year%' 判断。
--    只处理 408 科目。数学一来源名不在本轮批量改写范围（做题页用 examLabel 展示）。
-- -----------------------------------------------------------------------------
UPDATE question_resource
   SET source_name = CONCAT(exam_year, '年', TRIM(source_name)),
       updated_at = CURRENT_TIMESTAMP
 WHERE parent_question_id IS NULL
   AND subject_name LIKE '%408%'
   AND exam_year IS NOT NULL
   AND source_name IS NOT NULL
   AND TRIM(source_name) <> ''
   AND TRIM(source_name) NOT REGEXP CONCAT('^', exam_year);

-- -----------------------------------------------------------------------------
-- 5. 验证：是否还存在可确定性回填的 exam_year
--    期望结果：可回填行数为 0。
-- -----------------------------------------------------------------------------
SELECT COUNT(*) AS 仍可回填年份行数
  FROM question_resource
 WHERE parent_question_id IS NULL
   AND exam_year IS NULL
   AND (question_number REGEXP '^[0-9]{4}-'
        OR ((question_number IS NULL OR question_number NOT REGEXP '^[0-9]{4}-')
            AND source_name REGEXP '^[0-9]{4}'));

-- -----------------------------------------------------------------------------
-- 6. 验证：408 是否仍存在缺年份前缀的来源名
--    期望结果：缺前缀行数为 0。
-- -----------------------------------------------------------------------------
SELECT COUNT(*) AS cs408缺年份前缀行数
  FROM question_resource
 WHERE parent_question_id IS NULL
   AND subject_name LIKE '%408%'
   AND exam_year IS NOT NULL
   AND TRIM(source_name) NOT REGEXP CONCAT('^', exam_year);

-- -----------------------------------------------------------------------------
-- 7. 验证：是否出现重复年份前缀（例如 “2014年2014年…”）
--    期望结果：行数为 0。
-- -----------------------------------------------------------------------------
SELECT COUNT(*) AS 重复年份前缀行数
  FROM question_resource
 WHERE parent_question_id IS NULL
   AND source_name REGEXP '^([0-9]{4})年?.*\\1';

-- -----------------------------------------------------------------------------
-- 8. 验证：每个科目的来源名与年份分布（人工确认展示标签是否符合预期）
-- -----------------------------------------------------------------------------
SELECT subject_name,
       exam_year,
       source_name,
       COUNT(*) AS 题数
  FROM question_resource
 WHERE parent_question_id IS NULL
 GROUP BY subject_name, exam_year, source_name
 ORDER BY subject_name, exam_year, source_name;

-- -----------------------------------------------------------------------------
-- 9. unresolved：无法确定性推断年份的记录（不要猜，输出给用户）
--    期望结果：空集。
-- -----------------------------------------------------------------------------
SELECT id, subject_name, source_name, question_number, question_type, status
  FROM question_resource
 WHERE parent_question_id IS NULL
   AND exam_year IS NULL
 ORDER BY subject_name, question_number, id;

package cn.tihaishitu.learning;

/**
 * 知识点正式题覆盖口径的唯一事实来源。
 *
 * <p>长期规则：一个 KnowledgePoint 的“正式题集合”定义为与该知识点存在
 * {@code question_resource_knowledge} 关系的所有 published Formal Parent Question，
 * <b>core 与 auxiliary 同等计入</b>。{@code relation_role} 只用于知识标签主次显示与
 * 诊断 / 内容解释，不再决定题目能不能做，也不再决定是否进入 Mastery 分母。</p>
 *
 * <p>UI 题数、Mastery 分母、专项候选必须共用本类的 SQL 片段，避免再次出现三套统计口径。</p>
 */
public final class KnowledgeQuestionCoveragePolicy {
    private KnowledgeQuestionCoveragePolicy() {}

    /** 正式题关系角色：core 与 auxiliary 都算覆盖。 */
    public static String anyRelationRole(String relationAlias) {
        return relationAlias + ".relation_role IN ('core','auxiliary')";
    }

    /**
     * 知识点 ID 通过关系表关联到 published 正式父题的存在性判定。
     *
     * @param knowledgeAlias 外层知识点表的别名（通常是 {@code k}）
     * @param relationAlias  关系表别名（通常是 {@code qk}）
     * @param questionAlias  题目表别名（通常是 {@code q}）
     */
    public static String exists(String knowledgeAlias, String relationAlias, String questionAlias) {
        return """
                EXISTS (
                    SELECT 1
                      FROM question_resource_knowledge %s
                      JOIN question_resource %s ON %s.id = %s.question_id
                     WHERE %s.knowledge_point_id = %s.id
                       AND %s
                       AND %s
                )
                """.formatted(relationAlias, questionAlias, questionAlias, relationAlias,
                relationAlias, knowledgeAlias, anyRelationRole(relationAlias),
                FormalQuestionPolicy.published(questionAlias)).trim();
    }

    /** 默认别名版本：k / qk / q。 */
    public static String exists(String knowledgeAlias) {
        return exists(knowledgeAlias, "coverage_qk", "coverage_q");
    }

    /**
     * 单个 KnowledgePoint 的正式父题数量（去重题目 ID）。
     * Mastery 分母与 UI 题数必须共用该口径。
     */
    public static String countSql(String knowledgePointParameter) {
        return """
                SELECT COUNT(DISTINCT coverage_q.id)
                  FROM question_resource_knowledge coverage_qk
                  JOIN question_resource coverage_q ON coverage_q.id = coverage_qk.question_id
                 WHERE coverage_qk.knowledge_point_id = %s
                   AND %s
                   AND %s
                """.formatted(knowledgePointParameter, anyRelationRole("coverage_qk"),
                FormalQuestionPolicy.published("coverage_q")).trim();
    }
}

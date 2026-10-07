package cn.tihaishitu.learning;

/**
 * 正式 Attempt 的来源模式，落到 {@code study_attempt.draw_mode}。
 *
 * <p>长期规则：四套正式选题策略各自独立，Attempt 创建时必须显式写明来源，
 * 不允许再靠 {@code world_id} 或 {@code learner_practice_session.intent} 反推历史。</p>
 *
 * <pre>
 * RANDOM    Learner World / 寒门仕途普通随机正式题
 * CHAPTER   章节练习
 * KNOWLEDGE 知识点专项
 * WRONG     单题错题重做 / 错题快练
 * LEGACY    /games/** 兼容路径
 * </pre>
 *
 * <p>历史 diagnosis / remedial 兼容路径可以继续写 NULL，本 PR 不回填旧数据。</p>
 */
public enum PracticeDrawMode {
    RANDOM("random"),
    CHAPTER("chapter"),
    KNOWLEDGE("knowledge"),
    WRONG("wrong"),
    LEGACY("legacy");

    private final String wireValue;

    PracticeDrawMode(String wireValue) { this.wireValue = wireValue; }

    /** 数据库与 API 使用的稳定取值。 */
    public String wireValue() { return wireValue; }

    /** Hub Practice intent → 正式 draw_mode；未识别的 intent 返回 null。 */
    public static PracticeDrawMode forIntent(String intent) {
        if (intent == null) return null;
        return switch (intent) {
            case "knowledge_drill" -> KNOWLEDGE;
            case "chapter_drill" -> CHAPTER;
            case "wrong_review", "wrong_drill" -> WRONG;
            default -> null;
        };
    }
}

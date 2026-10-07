package cn.tihaishitu.learning;

/**
 * RANDOM 策略内部实际走到的 lane，落到 {@code study_attempt.draw_reason}。
 *
 * <pre>
 * oldest          该 Learner × KnowledgePoint 的 oldest lane
 * wrong           该 Learner × KnowledgePoint 的 wrong lane（命中 active 永久错题）
 * wrong_fallback  wrong lane 没有可用错题，回退 oldest（这个 lane slot 仍然算消费）
 * </pre>
 *
 * <p>非 RANDOM 模式允许 NULL，不写无意义的占位值。</p>
 */
public final class PracticeDrawReason {
    public static final String OLDEST = "oldest";
    public static final String WRONG = "wrong";
    public static final String WRONG_FALLBACK = "wrong_fallback";

    private PracticeDrawReason() {}
}

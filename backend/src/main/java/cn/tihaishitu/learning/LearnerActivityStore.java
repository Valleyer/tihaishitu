package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「有效 Attempt」事实的唯一 SQL 来源（PR7 进度统计 V3）。
 *
 * <p>一次 Attempt 只投影成一次 {@link EffectiveAction}：计数单位是 {@code study_attempt.id}，
 * 同一道题的不同有效 Attempt 各计 1 次；{@code status=revealed} 与 {@code status=graded} 都
 * 可能是有效事件，结果分类由 {@link #outcome} 决定，其中 {@link Outcome#REVEALED_ONLY} 表示
 * 「仅查看答案」，绝不写成 wrong，也不产生 Mastery / Evidence / 错题本。</p>
 *
 * <p>只读：不修改 grading、Mastery V3、永久错题本或 PR6 选题策略。所有上层统计（统一
 * {@code activity}、兼容的 graded-only {@code recent}、最近接触知识点）都必须复用这里，
 * 不允许各自再写一套聚合 SQL。</p>
 */
@Repository
public class LearnerActivityStore {
    /** 范围条件一次最多传入的 KnowledgePoint 数量，避免超出 JDBC 参数上限。 */
    private static final int SCOPE_BATCH = 500;

    private final JdbcTemplate jdbc;

    public LearnerActivityStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 读取给定范围内全部历史的有效 Attempt。
     *
     * <p>按冻结的 {@code target_knowledge_point_id} 判定归属：题目另外绑定的 KnowledgePoint
     * 不会让一次 Attempt 在多处重复记账。多本文集共享同一 KnowledgePoint 时范围集合已去重，
     * 因此只传去重后的 ID，不因多对多关系放大行数。</p>
     */
    public List<EffectiveAction> effectiveActions(String learnerId, Set<String> scope) {
        if (scope.isEmpty()) return List.of();
        Map<String, EffectiveAction> actions = new LinkedHashMap<>();
        for (List<String> batch : batches(scope)) {
            for (AttemptRow row : attemptRows(learnerId, batch)) {
                Instant actionAt = actionAt(row);
                actions.putIfAbsent(row.attemptId(), new EffectiveAction(row.attemptId(), row.knowledgePointId(),
                        outcome(row), actionAt, actionAt.atZone(PracticeBusinessDay.ZONE).toLocalDate(),
                        row.assessment(), row.answeredAt()));
            }
        }
        return List.copyOf(actions.values());
    }

    /**
     * 一次 Attempt 只产生一次有效事件，时间取「首次有效行动」。
     *
     * <p>{@code revealed} 取 {@code answer_revealed_at}；已 reveal 过的 {@code graded} 沿用更早的
     * 首次有效行动时间，之后自评只更新结果分类而不产生第二次有效事件；其他 {@code graded} 取
     * {@code answered_at}。SQL 已保证被采纳的时间列非空，因此不虚构日期。</p>
     */
    private static Instant actionAt(AttemptRow row) {
        if ("revealed".equals(row.status())) return row.revealedAt();
        return row.revealedAt() != null && row.revealedAt().isBefore(row.answeredAt())
                ? row.revealedAt() : row.answeredAt();
    }

    /**
     * 结果分类。
     *
     * <p>{@code status='graded'} 的记录一定有 assessment 列的值，但历史上可能出现非标准取值
     * （{@code skipped} 之类）。这类记录仍是一次真实评分事实，因此归入
     * {@link Outcome#OTHER}：它不进结果分布，但历史七日的 graded-only 兼容字段必须把它算进去
     * （旧契约只要求 {@code status='graded'} + {@code answered_at}）。</p>
     *
     * <p>{@code revealed} 只归类为「仅查看答案」。</p>
     */
    private static Outcome outcome(AttemptRow row) {
        if ("revealed".equals(row.status())) return Outcome.REVEALED_ONLY;
        return switch (row.assessment() == null ? "" : row.assessment()) {
            case "correct" -> Outcome.CORRECT;
            case "partial" -> Outcome.PARTIAL;
            case "wrong" -> Outcome.WRONG;
            default -> Outcome.OTHER;
        };
    }

    private List<AttemptRow> attemptRows(String learnerId, List<String> scope) {
        String sql = """
                SELECT a.id,a.target_knowledge_point_id,a.status,a.assessment,a.answered_at,a.answer_revealed_at
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                 WHERE a.learner_id=? AND a.status IN ('graded','revealed')
                   AND a.target_knowledge_point_id IS NOT NULL
                   AND ((a.status='revealed' AND a.answer_revealed_at IS NOT NULL)
                        OR (a.status='graded' AND a.answered_at IS NOT NULL))
                   AND %s
                   AND a.target_knowledge_point_id IN (%s)
                """.formatted(FormalQuestionPolicy.published("q"), placeholders(scope.size()));
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.addAll(scope);
        return jdbc.query(sql, (rs, index) -> new AttemptRow(rs.getString("id"),
                rs.getString("target_knowledge_point_id"), rs.getString("status"), rs.getString("assessment"),
                instant(rs.getTimestamp("answered_at")), instant(rs.getTimestamp("answer_revealed_at"))),
                args.toArray());
    }

    private static List<List<String>> batches(Set<String> values) {
        List<String> ordered = List.copyOf(values);
        List<List<String>> batches = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += SCOPE_BATCH) {
            batches.add(ordered.subList(start, Math.min(ordered.size(), start + SCOPE_BATCH)));
        }
        return batches;
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /**
     * 有效答题的结果分类；{@code REVEALED_ONLY} 表示「仅查看答案」，{@code OTHER} 表示
     * {@code status='graded'} 但 assessment 非标准取值的历史记录。
     */
    public enum Outcome { CORRECT, PARTIAL, WRONG, REVEALED_ONLY, OTHER }

    /**
     * 一次有效 Attempt。
     *
     * <p>{@code actionAt} 是首次有效行动时刻（reveal 或 graded），{@code actionDate} 是它所在的
     * 上海业务日 — 两者表示「有效接触」，与「Mastery 最后证据时间」是两个不同概念。
     * {@code answeredAt} 是评分时刻（仅 graded 有值），保留给需要按<b>评分日</b>归属的历史兼容
     * 字段使用；{@code assessment} 保留原始结果值供 UI 描述真实行为。</p>
     */
    public record EffectiveAction(String attemptId, String knowledgePointId, Outcome outcome,
                                  Instant actionAt, LocalDate actionDate,
                                  String assessment, Instant answeredAt) {}

    private record AttemptRow(String attemptId, String knowledgePointId, String status, String assessment,
                              Instant answeredAt, Instant revealedAt) {}
}

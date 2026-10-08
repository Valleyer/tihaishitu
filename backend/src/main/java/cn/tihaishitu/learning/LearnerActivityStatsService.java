package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 进度与统计的统一「有效答题」事实来源（PR7 进度统计 V3）。
 *
 * <p>长期规则：进度页、统计兼容接口和任何后续统计展示必须共用本类口径，不得再各自写
 * 一套 SQL。口径记录在 {@code docs/PROJECT_RULES.md}「进度统计 V3」一节。</p>
 *
 * <p>计数单位是 {@code study_attempt.id}：同一道题的不同有效 Attempt 各计 1 次；一次
 * Attempt 先 reveal 再自评仍然只计 1 次；仅打开后直接退出的 {@code active}、Legacy 无
 * Learner 身份、Remedial 子题、非正式题与只读浏览都不计数。</p>
 *
 * <p>本类只读：不修改 grading、Mastery V3、永久错题本或 PR6 选题策略。</p>
 */
@Service
public class LearnerActivityStatsService {

    /** 活跃学习日与趋势曲线固定的业务日窗口长度。 */
    public static final int WINDOW_DAYS = 7;

    private static final int SCOPE_BATCH = 500;

    private final JdbcTemplate jdbc;
    private final LearnerProgressService progress;
    private final Clock clock = Clock.systemUTC();

    public LearnerActivityStatsService(JdbcTemplate jdbc, LearnerProgressService progress) {
        this.jdbc = jdbc;
        this.progress = progress;
    }

    /** 当前登录 Learner 的统一活动统计。 */
    public ActivityView current() {
        return activityAt(LearnerContext.learnerId(), clock.instant(), WINDOW_DAYS);
    }

    /**
     * 统一活动统计。
     *
     * <p>累计类指标永远是当前学习范围内的 <b>全历史</b> 事实，不随 {@code windowDays}
     * 截断；{@code windowDays} 只决定 {@code daily} 曲线的长度。</p>
     */
    public ActivityView activityAt(String learnerId, Instant now, int windowDays) {
        LocalDate today = now.atZone(PracticeBusinessDay.ZONE).toLocalDate();
        Set<String> scope = progress.scopedKnowledgePointIds(learnerId, now);
        List<EffectiveAction> actions = effectiveActions(learnerId, scope);

        int correct = 0, partial = 0, wrong = 0, revealedOnly = 0, todayAttempts = 0;
        Set<String> touchedPoints = new LinkedHashSet<>();
        Map<LocalDate, List<EffectiveAction>> byDate = new LinkedHashMap<>();
        LocalDate windowStart = today.minusDays(windowDays - 1L);
        for (EffectiveAction action : actions) {
            switch (action.outcome()) {
                case CORRECT -> correct++;
                case PARTIAL -> partial++;
                case WRONG -> wrong++;
                case REVEALED_ONLY -> revealedOnly++;
            }
            touchedPoints.add(action.knowledgePointId());
            if (action.actionDate().equals(today)) todayAttempts++;
            if (!action.actionDate().isBefore(windowStart) && !action.actionDate().isAfter(today)) {
                byDate.computeIfAbsent(action.actionDate(), ignored -> new ArrayList<>()).add(action);
            }
        }

        List<DailyActivity> daily = new ArrayList<>();
        int activeStudyDays = 0;
        for (LocalDate date = windowStart; !date.isAfter(today); date = date.plusDays(1)) {
            DailyActivity day = dailyActivity(date, byDate.getOrDefault(date, List.of()));
            if (day.effectiveAttempts() > 0) activeStudyDays++;
            daily.add(day);
        }

        Metrics metrics = new Metrics(activeStudyDays, todayAttempts, scope.size(), touchedPoints.size(),
                actions.size(), correct);
        OutcomeSummary outcomes = new OutcomeSummary(correct, partial, wrong, revealedOnly);
        return new ActivityView(windowDays, now, metrics, outcomes, daily);
    }

    private static DailyActivity dailyActivity(LocalDate date, List<EffectiveAction> actions) {
        Set<String> points = new LinkedHashSet<>();
        int correct = 0, partial = 0, wrong = 0, revealedOnly = 0;
        for (EffectiveAction action : actions) {
            points.add(action.knowledgePointId());
            switch (action.outcome()) {
                case CORRECT -> correct++;
                case PARTIAL -> partial++;
                case WRONG -> wrong++;
                case REVEALED_ONLY -> revealedOnly++;
            }
        }
        return new DailyActivity(date, actions.size(), points.size(), correct, partial, wrong, revealedOnly);
    }

    /**
     * 读取当前范围内全部历史有效答题。
     *
     * <p>按冻结的 {@code target_knowledge_point_id} 判定归属：题目另外绑定的 KnowledgePoint
     * 不会让一次 Attempt 在多处重复记账。多本文集共享同一 KnowledgePoint 时范围集合已经去重，
     * 因此范围条件只传去重后的 ID，不会因多对多关系放大行数。</p>
     */
    private List<EffectiveAction> effectiveActions(String learnerId, Set<String> scope) {
        if (scope.isEmpty()) return List.of();
        Map<String, EffectiveAction> actions = new LinkedHashMap<>();
        for (List<String> batch : batches(scope)) {
            for (AttemptRow row : attemptRows(learnerId, batch)) {
                Outcome outcome = outcome(row).orElse(null);
                if (outcome == null) continue;
                LocalDate date = actionAt(row).atZone(PracticeBusinessDay.ZONE).toLocalDate();
                actions.putIfAbsent(row.attemptId(),
                        new EffectiveAction(row.attemptId(), row.knowledgePointId(), outcome, date));
            }
        }
        return List.copyOf(actions.values());
    }

    /**
     * 一次 Attempt 只产生一次有效事件。
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
     * <p>{@code graded} 但 assessment 缺失或非法的历史记录无法判定，明确排除而不是编造结果；
     * {@code revealed} 只归类为「仅查看答案」，绝不写成 wrong，也不算掌握。</p>
     */
    private static Optional<Outcome> outcome(AttemptRow row) {
        if ("revealed".equals(row.status())) return Optional.of(Outcome.REVEALED_ONLY);
        return switch (row.assessment() == null ? "" : row.assessment()) {
            case "correct" -> Optional.of(Outcome.CORRECT);
            case "partial" -> Optional.of(Outcome.PARTIAL);
            case "wrong" -> Optional.of(Outcome.WRONG);
            default -> Optional.empty();
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

    /** 有效答题的结果分类；{@code revealed_only} 表示「仅查看答案」，不可当成错误或掌握。 */
    public enum Outcome { CORRECT, PARTIAL, WRONG, REVEALED_ONLY }

    /** 一次有效 Attempt；{@code actionDate} 是首次有效行动的上海业务日。 */
    public record EffectiveAction(String attemptId, String knowledgePointId, Outcome outcome, LocalDate actionDate) {}

    private record AttemptRow(String attemptId, String knowledgePointId, String status, String assessment,
                              Instant answeredAt, Instant revealedAt) {}

    /** 六个核心指标，与前端字段一一对应。 */
    public record Metrics(int activeStudyDays7d, int todayEffectiveAttempts, int totalKnowledgePoints,
                          int touchedKnowledgePoints, int totalEffectiveAttempts, int totalCorrectAttempts) {}

    /** 结果分布；四类之和等于 {@code totalEffectiveAttempts}。 */
    public record OutcomeSummary(int correct, int partial, int wrong, int revealedOnly) {}

    public record DailyActivity(LocalDate date, int effectiveAttempts, int distinctKnowledgePoints,
                                int correct, int partial, int wrong, int revealedOnly) {}

    public record ActivityView(int windowDays, Instant generatedAt, Metrics metrics, OutcomeSummary outcomes,
                               List<DailyActivity> daily) {}
}

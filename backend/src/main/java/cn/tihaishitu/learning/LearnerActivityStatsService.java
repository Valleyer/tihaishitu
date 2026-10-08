package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 进度与统计的统一「有效答题」事实来源（PR7 进度统计 V3）。
 *
 * <p>长期规则：进度页、统计兼容接口和任何后续统计展示必须共用本类口径，不得再各自写
 * 一套 SQL。SQL 本身在 {@link LearnerActivityStore}；口径记录在
 * {@code docs/PROJECT_RULES.md}「进度统计 V3」一节。</p>
 *
 * <p>一次 {@link #views} 调用只读一次全历史 Attempt，然后同时产出三份互不混淆的投影：</p>
 * <ul>
 *   <li>{@link ActivityView}：完整有效答题口径（含 reveal-only），日期取<b>首次有效行动</b>；</li>
 *   <li>{@link GradedRecentView}：旧 {@code recent} 兼容契约，<b>graded-only</b> 且日期取
 *       {@code answered_at}（评分日）；</li>
 *   <li>{@link RecentContact}：最近接触列表，日期取首次有效行动，含 reveal-only。</li>
 * </ul>
 *
 * <p>本类只读：不修改 grading、Mastery V3、永久错题本或 PR6 选题策略。</p>
 */
@Service
public class LearnerActivityStatsService {

    /** 活跃学习日与趋势曲线固定的业务日窗口长度。 */
    public static final int WINDOW_DAYS = 7;

    /**
     * 只有这三类真实评分结果才进入统一有效答题口径；reveal-only 单独成类。
     *
     * <p>{@link LearnerActivityStore.Outcome#OTHER}（assessment 非标准取值的历史记录）既不算
     * 「有效 Attempt」，也不算任何结果分类，避免编造结果。</p>
     */
    private static final Set<LearnerActivityStore.Outcome> SCORED_OUTCOMES =
            Set.of(LearnerActivityStore.Outcome.CORRECT, LearnerActivityStore.Outcome.PARTIAL,
                    LearnerActivityStore.Outcome.WRONG);

    /** 旧 {@code recent.knowledgePoints}（Evidence 列表）的展示上限。 */
    public static final int RECENT_EVIDENCE_LIMIT = 10;

    private final LearnerActivityStore store;
    private final LearnerProgressStore progress;
    private final Clock clock = Clock.systemUTC();

    public LearnerActivityStatsService(LearnerActivityStore store, LearnerProgressStore progress) {
        this.store = store;
        this.progress = progress;
    }

    /** 当前登录 Learner 的最近接触知识点（含仅查看答案）。 */
    public List<RecentContact> recentContacts() {
        return contactsAt(LearnerContext.learnerId(), clock.instant());
    }

    /**
     * 统一活动统计。
     *
     * <p>累计类指标永远是当前学习范围内的 <b>全历史</b> 事实，不随 {@code windowDays}
     * 截断；{@code windowDays} 只决定 {@code daily} 曲线的长度。</p>
     */
    public ActivityView activityAt(String learnerId, Instant now, int windowDays) {
        Set<String> scope = scopedKnowledgePointIds(learnerId);
        return activityView(store.effectiveActions(learnerId, scope), scope, now, windowDays);
    }

    /** 指定 Learner 在当前学习范围内的最近接触知识点，按最近有效接触时间倒序。 */
    public List<RecentContact> contactsAt(String learnerId, Instant now) {
        return buildContacts(store.effectiveActions(learnerId, scopedKnowledgePointIds(learnerId)));
    }

    /**
     * 一次只读派生全部进度视图事实。
     *
     * <p>调用方必须复用这里的结果，不要为了拿 {@code activity} 再单独调用一次统计接口，
     * 否则同一个请求会重复扫描全历史 Attempt，并可能在两个时间点读到不同快照。</p>
     *
     * <p>{@code trendDays} 只影响 {@code activity.daily} 的曲线窗口长度（进度页 7/30/90 天切换）；
     * {@code activity.metrics}、{@code activity.outcomes} 与旧 {@code recent} 的 graded-only 七日
     * 字段始终保持固定口径，不随切换静默改变语义。</p>
     */
    public Views views(String learnerId, Instant now, int trendDays) {
        Set<String> scope = scopedKnowledgePointIds(learnerId);
        List<LearnerActivityStore.EffectiveAction> actions = store.effectiveActions(learnerId, scope);
        return new Views(activityView(actions, scope, now, trendDays),
                gradedRecentView(actions, now), buildContacts(actions));
    }

    /** 当前学习范围内、可学习的去重 KnowledgePoint 集合。 */
    public Set<String> scopedKnowledgePointIds(String learnerId) {
        Map<String, LearnerProgressStore.MembershipRow> pointById = new LinkedHashMap<>();
        progress.selectedMemberships(learnerId).forEach(row -> pointById.putIfAbsent(row.knowledgePointId(), row));
        return pointById.keySet();
    }

    /**
     * 完整有效答题口径。
     *
     * <p>日期取<b>首次有效行动</b>（reveal 或 graded），因此跨天 reveal→自评仍归在 reveal 那天。
     * 结果分布只统计合法 assessment，reveal-only 单独一类。</p>
     *
     * <p>指标与结果分布固定按 {@link #WINDOW_DAYS} 结算（`activeStudyDays7d` 永远反映近 7 天），
     * 只有 {@code daily} 曲线按 {@code trendDays} 展开；唯一一次遍历同时产出两者，避免为不同窗口
     * 重复扫描历史。</p>
     */
    private ActivityView activityView(List<LearnerActivityStore.EffectiveAction> actions, Set<String> scope,
                                      Instant now, int trendDays) {
        LocalDate today = now.atZone(PracticeBusinessDay.ZONE).toLocalDate();
        LocalDate metricStart = today.minusDays(WINDOW_DAYS - 1L);
        LocalDate trendStart = today.minusDays(trendDays - 1L);

        int correct = 0, partial = 0, wrong = 0, revealedOnly = 0, todayAttempts = 0, effective = 0;
        Set<String> touchedPoints = new LinkedHashSet<>();
        // 活跃学习日固定按近 7 天结算，不随趋势窗口切换。
        Set<LocalDate> activeStudyDaysDate = new LinkedHashSet<>();
        Map<LocalDate, List<LearnerActivityStore.Outcome>> byDate = new LinkedHashMap<>();
        for (LearnerActivityStore.EffectiveAction action : actions) {
            LearnerActivityStore.Outcome outcome = action.outcome();
            // assessment 非标准取值的历史记录：既不算有效 Attempt，也不编造结果分类。
            if (outcome == LearnerActivityStore.Outcome.OTHER) continue;
            effective++;
            switch (outcome) {
                case CORRECT -> correct++;
                case PARTIAL -> partial++;
                case WRONG -> wrong++;
                case REVEALED_ONLY -> revealedOnly++;
                case OTHER -> { }
            }
            touchedPoints.add(action.knowledgePointId());
            if (action.actionDate().equals(today)) todayAttempts++;
            // 指标固定看近 7 天；趋势曲线看当前选择的窗口。
            if (!action.actionDate().isBefore(metricStart) && !action.actionDate().isAfter(today)) {
                activeStudyDaysDate.add(action.actionDate());
            }
            if (!action.actionDate().isBefore(trendStart) && !action.actionDate().isAfter(today)) {
                byDate.computeIfAbsent(action.actionDate(), ignored -> new ArrayList<>()).add(outcome);
            }
        }

        List<DailyActivity> daily = new ArrayList<>();
        for (LocalDate date = trendStart; !date.isAfter(today); date = date.plusDays(1)) {
            daily.add(dailyActivity(date, actions, byDate.getOrDefault(date, List.of())));
        }

        Metrics metrics = new Metrics(activeStudyDaysDate.size(), todayAttempts, scope.size(), touchedPoints.size(),
                effective, correct);
        OutcomeSummary outcomes = new OutcomeSummary(correct, partial, wrong, revealedOnly);
        return new ActivityView(trendDays, now, metrics, outcomes, daily);
    }

    private static DailyActivity dailyActivity(LocalDate date,
                                               List<LearnerActivityStore.EffectiveAction> actions,
                                               List<LearnerActivityStore.Outcome> outcomes) {
        int correct = 0, partial = 0, wrong = 0, revealedOnly = 0;
        for (LearnerActivityStore.Outcome outcome : outcomes) {
            switch (outcome) {
                case CORRECT -> correct++;
                case PARTIAL -> partial++;
                case WRONG -> wrong++;
                case REVEALED_ONLY -> revealedOnly++;
                case OTHER -> { }
            }
        }
        Set<String> points = new LinkedHashSet<>();
        for (LearnerActivityStore.EffectiveAction action : actions) {
            if (action.actionDate().equals(date)) points.add(action.knowledgePointId());
        }
        return new DailyActivity(date, outcomes.size(), points.size(), correct, partial, wrong, revealedOnly);
    }

    /**
     * 旧 {@code recent} 的 graded-only 七日投影。
     *
     * <p><b>日期语义与 {@code activity} 不同</b>：这里按 {@code answered_at}（评分日）归属上海
     * 业务日，沿用 PR7 之前的旧契约；{@code activity} / {@code recentContacts} 用首次有效行动日。
     * 因此同一个跨天 reveal→自评 Attempt 会分别出现在两个字段各自的日期上，这不是重复计数。</p>
     *
     * <p>只统计真实评分（非 reveal-only）的 Attempt，reveal-only 绝不填入带 {@code graded} 的
     * 字段；窗口同时检查起点与终点，避免未来时间的记录混入。</p>
     */
    private GradedRecentView gradedRecentView(List<LearnerActivityStore.EffectiveAction> actions, Instant now) {
        LocalDate today = now.atZone(PracticeBusinessDay.ZONE).toLocalDate();
        LocalDate windowStart = today.minusDays(WINDOW_DAYS - 1L);

        int graded7d = 0;
        Set<String> distinct7d = new LinkedHashSet<>();
        Map<LocalDate, Set<String>> pointsByDate = new LinkedHashMap<>();
        Map<LocalDate, Integer> gradedByDate = new LinkedHashMap<>();
        for (LearnerActivityStore.EffectiveAction action : actions) {
            if (!SCORED_OUTCOMES.contains(action.outcome())) continue;
            Instant answeredAt = action.answeredAt();
            if (answeredAt == null) continue;
            LocalDate date = answeredAt.atZone(PracticeBusinessDay.ZONE).toLocalDate();
            if (date.isBefore(windowStart) || date.isAfter(today)) continue;
            graded7d++;
            distinct7d.add(action.knowledgePointId());
            gradedByDate.merge(date, 1, Integer::sum);
            pointsByDate.computeIfAbsent(date, ignored -> new LinkedHashSet<>()).add(action.knowledgePointId());
        }

        List<DailyProgress> daily = new ArrayList<>();
        for (LocalDate date = windowStart; !date.isAfter(today); date = date.plusDays(1)) {
            daily.add(new DailyProgress(date, gradedByDate.getOrDefault(date, 0),
                    pointsByDate.getOrDefault(date, Set.of()).size()));
        }
        int activeDays = (int) daily.stream().filter(day -> day.gradedAttempts() > 0).count();
        return new GradedRecentView(graded7d, distinct7d.size(), activeDays, daily);
    }

    /**
     * 最近接触知识点：按最近一次「有效接触」时间倒序，包含仅查看答案。
     *
     * <p>{@code lastOutcomeRevealedOnly} 表达该 KnowledgePoint <b>最近一次</b>有效行动是不是
     * 仅查看答案，UI 必须用它判断行为标签，不得用 {@code evidenceCount} 反推。</p>
     */
    private static List<RecentContact> buildContacts(List<LearnerActivityStore.EffectiveAction> actions) {
        Map<String, RecentContact> latest = new LinkedHashMap<>();
        for (LearnerActivityStore.EffectiveAction action : actions) {
            RecentContact previous = latest.get(action.knowledgePointId());
            if (previous == null || action.actionAt().isAfter(previous.lastEffectiveContactAt())) {
                latest.put(action.knowledgePointId(),
                        new RecentContact(action.knowledgePointId(), action.actionAt(), action.actionDate(),
                                action.outcome() == LearnerActivityStore.Outcome.REVEALED_ONLY,
                                action.answeredAt() != null, action.assessment()));
            }
        }
        return latest.values().stream()
                .sorted(Comparator.comparing(RecentContact::lastEffectiveContactAt).reversed()
                        .thenComparing(RecentContact::knowledgePointId))
                .toList();
    }

    /** 六个核心指标，与前端字段一一对应。 */
    public record Metrics(int activeStudyDays7d, int todayEffectiveAttempts, int totalKnowledgePoints,
                          int touchedKnowledgePoints, int totalEffectiveAttempts, int totalCorrectAttempts) {}

    /** 结果分布；四类之和等于有效答题总数（assessment 非标准的记录不计入任一类）。 */
    public record OutcomeSummary(int correct, int partial, int wrong, int revealedOnly) {}

    public record DailyActivity(LocalDate date, int effectiveAttempts, int distinctKnowledgePoints,
                                int correct, int partial, int wrong, int revealedOnly) {}

    public record ActivityView(int windowDays, Instant generatedAt, Metrics metrics, OutcomeSummary outcomes,
                               List<DailyActivity> daily) {}

    /**
     * 最近接触的一个 KnowledgePoint。
     *
     * <p>{@code lastEffectiveContactAt} 是有效接触时间（reveal 或 graded 的首次有效行动），
     * 不是 Mastery 证据时间。</p>
     *
     * <p>{@code lastOutcomeRevealedOnly} 表示最近一次有效行动是否为仅查看答案，由最近一条
     * Attempt 的真实状态产生，不能由 {@code evidenceCount} 推断；{@code lastGraded} 表示最近
     * 一次有效行动已被真实评分（因此即使暂无 Mastery Evidence 也不该显示为「仅查看答案」）；
     * {@code assessment} 是最近一次的真实结果值，可为空。</p>
     */
    public record RecentContact(String knowledgePointId, Instant lastEffectiveContactAt, LocalDate actionDate,
                                boolean lastOutcomeRevealedOnly, boolean lastGraded, String assessment) {}

    /** 旧 {@code recent} 的 graded-only 数值（日期取评分日 {@code answered_at}）。 */
    public record GradedRecentView(int gradedAttempts7d, int distinctKnowledgePoints7d, int activeStudyDays7d,
                                   List<DailyProgress> daily) {}

    public record DailyProgress(LocalDate date, int gradedAttempts, int distinctKnowledgePoints) {}

    /** 一次只读的三个视图。调用方必须复用，不得再次查询统计事实。 */
    public record Views(ActivityView activity, GradedRecentView recent, List<RecentContact> contacts) {}
}

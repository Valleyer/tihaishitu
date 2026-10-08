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

    private final LearnerActivityStore store;
    private final LearnerProgressStore progress;
    private final Clock clock = Clock.systemUTC();

    public LearnerActivityStatsService(LearnerActivityStore store, LearnerProgressStore progress) {
        this.store = store;
        this.progress = progress;
    }

    /** 当前登录 Learner 的统一活动统计。 */
    public ActivityView current() {
        return activityAt(LearnerContext.learnerId(), clock.instant(), WINDOW_DAYS);
    }

    /** 当前登录 Learner 的最近接触知识点（含仅查看答案）。 */
    public List<RecentContact> recentContacts() {
        return contactsAt(LearnerContext.learnerId(), clock.instant());
    }

    /** 指定 Learner 在当前学习范围内的最近接触知识点，按最近有效接触时间倒序。 */
    public List<RecentContact> contactsAt(String learnerId, Instant now) {
        return buildContacts(store.effectiveActions(learnerId, scopedKnowledgePointIds(learnerId)));
    }

    /**
     * 统一活动统计。
     *
     * <p>累计类指标永远是当前学习范围内的 <b>全历史</b> 事实，不随 {@code windowDays}
     * 截断；{@code windowDays} 只决定 {@code daily} 曲线的长度。</p>
     */
    public ActivityView activityAt(String learnerId, Instant now, int windowDays) {
        return derive(learnerId, now, windowDays).activity();
    }

    /**
     * 当前范围下的完整派生事实。
     *
     * <p>只读一次有效 Attempt，同时产出统一 {@code activity} 与兼容的 graded-only
     * {@code recent}，避免同一份指标出现两套 SQL 或两次语义不同的聚合。</p>
     */
    public Derived derive(String learnerId, Instant now, int windowDays) {
        LocalDate today = now.atZone(PracticeBusinessDay.ZONE).toLocalDate();
        Set<String> scope = scopedKnowledgePointIds(learnerId);
        List<LearnerActivityStore.EffectiveAction> actions = store.effectiveActions(learnerId, scope);

        int correct = 0, partial = 0, wrong = 0, revealedOnly = 0, todayAttempts = 0;
        Set<String> touchedPoints = new LinkedHashSet<>();
        Map<LocalDate, List<LearnerActivityStore.EffectiveAction>> byDate = new LinkedHashMap<>();
        LocalDate windowStart = today.minusDays(windowDays - 1L);
        for (LearnerActivityStore.EffectiveAction action : actions) {
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
        ActivityView activity = new ActivityView(windowDays, now, metrics, outcomes, daily);
        RecentProgress recent = gradedRecent(actions, daily, today);
        return new Derived(activity, recent, buildContacts(actions));
    }

    /** 当前学习范围内、可学习的去重 KnowledgePoint 集合。 */
    public Set<String> scopedKnowledgePointIds(String learnerId) {
        Map<String, LearnerProgressStore.MembershipRow> pointById = new LinkedHashMap<>();
        progress.selectedMemberships(learnerId).forEach(row -> pointById.putIfAbsent(row.knowledgePointId(), row));
        return pointById.keySet();
    }

    /**
     * 兼容旧 {@code recent} 契约的 graded-only 投影。
     *
     * <p>字段名带 {@code graded}，因此只统计真实 {@code graded + correct/partial/wrong}：
     * 仅查看答案（{@code revealed_only}）绝不填入这些字段，避免字段名与历史语义背离。
     * 完整的有效答题口径（含 reveal-only）由 {@link ActivityView} 负责。</p>
     */
    private static RecentProgress gradedRecent(List<LearnerActivityStore.EffectiveAction> actions,
                                               List<DailyActivity> daily, LocalDate today) {
        LocalDate windowStart = today.minusDays(WINDOW_DAYS - 1L);
        Set<String> distinct7d = new LinkedHashSet<>();
        Map<LocalDate, Set<String>> pointsByDate = new LinkedHashMap<>();
        int graded7d = 0;
        for (LearnerActivityStore.EffectiveAction action : actions) {
            // 仅查看答案不计入任何 graded-only 兼容字段。
            if (action.outcome() == LearnerActivityStore.Outcome.REVEALED_ONLY) continue;
            if (action.actionDate().isBefore(windowStart)) continue;
            graded7d++;
            distinct7d.add(action.knowledgePointId());
            pointsByDate.computeIfAbsent(action.actionDate(), ignored -> new LinkedHashSet<>())
                    .add(action.knowledgePointId());
        }
        List<DailyProgress> gradedDaily = daily.stream()
                .map(day -> new DailyProgress(day.date(), day.correct() + day.partial() + day.wrong(),
                        pointsByDate.getOrDefault(day.date(), Set.of()).size()))
                .toList();
        int activeDays = (int) gradedDaily.stream().filter(day -> day.gradedAttempts() > 0).count();
        return new RecentProgress(graded7d, distinct7d.size(), activeDays, gradedDaily, List.of());
    }

    /**
     * 最近接触知识点：按最近一次「有效接触」时间倒序，包含仅查看答案。
     *
     * <p>同一 KnowledgePoint 的多次有效 Attempt 只保留最近一次；{@code revealedOnly} 表示该
     * 知识点最近一次有效行动是仅查看答案。这里只描述「接触」，掌握度仍由 Mastery 事实决定。</p>
     */
    private static List<RecentContact> buildContacts(List<LearnerActivityStore.EffectiveAction> actions) {
        Map<String, RecentContact> latest = new LinkedHashMap<>();
        for (LearnerActivityStore.EffectiveAction action : actions) {
            RecentContact previous = latest.get(action.knowledgePointId());
            if (previous == null || action.actionAt().isAfter(previous.lastEffectiveContactAt())) {
                latest.put(action.knowledgePointId(),
                        new RecentContact(action.knowledgePointId(), action.actionAt(), action.actionDate(),
                                action.outcome() == LearnerActivityStore.Outcome.REVEALED_ONLY));
            }
        }
        return latest.values().stream()
                .sorted(Comparator.comparing(RecentContact::lastEffectiveContactAt).reversed()
                        .thenComparing(RecentContact::knowledgePointId))
                .toList();
    }

    private static DailyActivity dailyActivity(LocalDate date,
                                               List<LearnerActivityStore.EffectiveAction> actions) {
        Set<String> points = new LinkedHashSet<>();
        int correct = 0, partial = 0, wrong = 0, revealedOnly = 0;
        for (LearnerActivityStore.EffectiveAction action : actions) {
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

    /** 六个核心指标，与前端字段一一对应。 */
    public record Metrics(int activeStudyDays7d, int todayEffectiveAttempts, int totalKnowledgePoints,
                          int touchedKnowledgePoints, int totalEffectiveAttempts, int totalCorrectAttempts) {}

    /** 结果分布；四类之和等于 {@code totalEffectiveAttempts}。 */
    public record OutcomeSummary(int correct, int partial, int wrong, int revealedOnly) {}

    public record DailyActivity(LocalDate date, int effectiveAttempts, int distinctKnowledgePoints,
                                int correct, int partial, int wrong, int revealedOnly) {}

    public record ActivityView(int windowDays, Instant generatedAt, Metrics metrics, OutcomeSummary outcomes,
                               List<DailyActivity> daily) {}

    /**
     * 最近接触的一个 KnowledgePoint。
     *
     * <p>{@code lastEffectiveContactAt} 是有效接触时间（reveal 或 graded 的首次有效行动），
     * 不是 Mastery 证据时间；{@code revealedOnly} 表示当前范围内该知识点最近一次有效行动
     * 只有「查看参考解析」，尚无任何 Mastery Evidence。</p>
     */
    public record RecentContact(String knowledgePointId, Instant lastEffectiveContactAt, LocalDate actionDate,
                                boolean revealedOnly) {}

    /** 一次只读派生：统一 {@code activity} + 兼容 graded-only {@code recent} + 最近接触。 */
    public record Derived(ActivityView activity, RecentProgress recent, List<RecentContact> contacts) {}

    /** 旧 {@code recent} 契约（graded-only）；保留字段名、类型与七天业务日语义。 */
    public record RecentProgress(int gradedAttempts7d, int distinctKnowledgePoints7d, int activeStudyDays7d,
                                 List<DailyProgress> daily, List<RecentKnowledgePoint> knowledgePoints) {}

    public record DailyProgress(LocalDate date, int gradedAttempts, int distinctKnowledgePoints) {}

    public record RecentKnowledgePoint(String knowledgePointId, String name, String bookName, String chapterName,
                                       String band, double effectiveMastery, double stabilityDays,
                                       Instant lastEvidenceAt) {}
}

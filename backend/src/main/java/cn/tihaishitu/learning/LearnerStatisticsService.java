package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 旧 `/learner/statistics` 的兼容实现（PR7 进度统计 V3）。
 *
 * <p>统一口径的唯一事实来源是 {@link LearnerActivityStatsService}：指标、结果分布、有效
 * Attempt 规则与当前学习范围全部从那里派生，所以 {@code summary} 与
 * {@code /learner/progress.activity} 的数值必然一致，绝不出现两套数字。</p>
 *
 * <p><b>出场分布字段是 graded-only。</b> {@code knowledgeDrillAttempts / wrongReviewAttempts /
 * worldAttempts} 沿用历史含义，只统计真实 {@code graded + correct/partial/wrong} 的正式
 * Attempt，<b>不含</b>仅查看答案（reveal-only）。范围判定复用
 * {@link LearnerActivityStatsService#scopedKnowledgePointIds}，不再自带第二套范围 SQL。</p>
 *
 * <p>旧契约的 {@code days=7|30|90} 入参继续接受，只影响 {@code daily} 曲线长度；UI 只展示近 7 天。</p>
 */
@Service
public class LearnerStatisticsService {
    private static final Set<Integer> SUPPORTED_DAYS = Set.of(7, 30, 90);

    private final JdbcTemplate jdbc;
    private final LearnerActivityStatsService activity;
    private final LearnerProgressService progress;
    private final Clock clock = Clock.systemUTC();

    public LearnerStatisticsService(JdbcTemplate jdbc, LearnerActivityStatsService activity,
                                    LearnerProgressService progress) {
        this.jdbc = jdbc;
        this.activity = activity;
        this.progress = progress;
    }

    public StatisticsView current(int days) {
        if (!SUPPORTED_DAYS.contains(days)) {
            throw new ResponseStatusException(BAD_REQUEST, "统计范围只支持 7、30 或 90 天。");
        }
        String learnerId = LearnerContext.learnerId();
        Instant now = clock.instant();

        LearnerActivityStatsService.ActivityView unified = activity.activityAt(learnerId, now,
                LearnerActivityStatsService.WINDOW_DAYS);
        LearnerActivityStatsService.Metrics metrics = unified.metrics();

        Origins origins = gradedOrigins(learnerId, activity.scopedKnowledgePointIds(learnerId));
        Summary summary = new Summary(metrics.totalEffectiveAttempts(), metrics.activeStudyDays7d(),
                metrics.touchedKnowledgePoints(), origins.knowledgeDrill(), origins.wrongReview(),
                origins.world(), unified.outcomes().correct(), unified.outcomes().partial(),
                unified.outcomes().wrong(), unified.outcomes().revealedOnly());

        List<Daily> daily = daily(learnerId, now, days);
        List<BookMastery> books = progress.progressAt(learnerId, now).books().stream()
                .map(book -> new BookMastery(book.bookId(), book.name(), book.masteryProgress(),
                        book.totalKnowledgePoints())).toList();
        return new StatisticsView(days, now, summary, daily, books);
    }

    /**
     * 旧接口的出场分布字段（graded-only）。
     *
     * <p>只读取 {@code status='graded'} 且 assessment 合法的正式 Attempt：{@code revealed} 尚未
     * 评分，没有 assessment，既不能算进结果分布，也不能凭空归类到任何出场分布里。</p>
     *
     * <p>范围收敛在 Java 侧按冻结 {@code target_knowledge_point_id} 判定，直接复用统一活动
     * 统计已经算好的去重范围集合；这样多本文集共享同一 KnowledgePoint 时既不会漏算，也不会
     * 通过多对多 JOIN 放大行数，更不会出现第二套范围口径。</p>
     */
    private Origins gradedOrigins(String learnerId, Set<String> scope) {
        if (scope.isEmpty()) return new Origins(0, 0, 0);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.target_knowledge_point_id,a.world_id,p.intent
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                  LEFT JOIN learner_practice_session p ON p.id=a.practice_session_id
                 WHERE a.learner_id=? AND a.status='graded' AND a.answered_at IS NOT NULL
                   AND a.assessment IN ('correct','partial','wrong')
                   AND a.target_knowledge_point_id IS NOT NULL
                   AND %s
                """.formatted(FormalQuestionPolicy.published("q")), learnerId);
        int knowledgeDrill = 0, wrongReview = 0, world = 0;
        for (Map<String, Object> row : rows) {
            // 冻结 target 不在当前学习范围内：只隐藏历史，不删除记录。
            if (!scope.contains(String.valueOf(row.get("target_knowledge_point_id")))) continue;
            if ("knowledge_drill".equals(row.get("intent"))) knowledgeDrill++;
            // 错题单题重做与错题快练统一计入错题练习作答统计。
            if ("wrong_review".equals(row.get("intent")) || "wrong_drill".equals(row.get("intent"))) wrongReview++;
            if (row.get("world_id") != null) world++;
        }
        return new Origins(knowledgeDrill, wrongReview, world);
    }

    private List<Daily> daily(String learnerId, Instant now, int days) {
        return activity.activityAt(learnerId, now, days).daily().stream()
                .map(day -> new Daily(day.date(), day.effectiveAttempts(), day.distinctKnowledgePoints())).toList();
    }

    public record StatisticsView(int days, Instant generatedAt, Summary summary,
                                 List<Daily> daily, List<BookMastery> books) {}
    /**
     * {@code gradedAttempts / activeStudyDays / distinctKnowledgePoints / correct / partial / wrong /
     * revealedOnly} 与 {@code /learner/progress.activity} 同源（含 reveal-only）；
     * {@code knowledgeDrillAttempts / wrongReviewAttempts / worldAttempts} 是 graded-only 出场分布。
     */
    public record Summary(int gradedAttempts, int activeStudyDays, int distinctKnowledgePoints,
                          int knowledgeDrillAttempts, int wrongReviewAttempts, int worldAttempts,
                          int correct, int partial, int wrong, int revealedOnly) {}
    public record Daily(LocalDate date, int gradedAttempts, int distinctKnowledgePoints) {}
    public record BookMastery(String bookId, String name, double masteryProgress, int knowledgePointCount) {}

    private record Origins(int knowledgeDrill, int wrongReview, int world) {}
}

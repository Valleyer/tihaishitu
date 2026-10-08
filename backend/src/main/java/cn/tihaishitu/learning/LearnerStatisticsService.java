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
 * Attempt 规则与当前学习范围全部从那里派生，本类只额外保留旧接口的
 * {@code knowledgeDrillAttempts / wrongReviewAttempts / worldAttempts} 出场分布字段，
 * 且这些字段同样只统计同一个有效 Attempt 集合。</p>
 *
 * <p>旧契约的 {@code days=7|30|90} 入参继续接受，只影响 {@code daily} 曲线长度；{@code summary}
 * 数值与 `/learner/progress.activity` 完全一致，绝不出现两套数字。UI 只展示近 7 天。</p>
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

        Origins origins = origins(learnerId);
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
     * 旧接口的出场分布字段。
     *
     * <p>只统计与统一统计同一条有效 Attempt 规则下的记录：{@code revealed} 尚未评分，没有
     * assessment，不能凭空归类到任何出场分布里。</p>
     */
    private Origins origins(String learnerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.assessment,a.world_id,p.intent
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                  LEFT JOIN learner_practice_session p ON p.id=a.practice_session_id
                 WHERE a.learner_id=? AND a.status='graded' AND a.answered_at IS NOT NULL
                   AND a.assessment IN ('correct','partial','wrong')
                   AND %s
                   AND %s
                """.formatted(FormalQuestionPolicy.published("q"), scopedTargetExists()), learnerId);
        int knowledgeDrill = 0, wrongReview = 0, world = 0;
        for (Map<String, Object> row : rows) {
            if ("knowledge_drill".equals(row.get("intent"))) knowledgeDrill++;
            // 错题单题重做与错题快练统一计入错题练习作答统计。
            if ("wrong_review".equals(row.get("intent")) || "wrong_drill".equals(row.get("intent"))) wrongReview++;
            if (row.get("world_id") != null) world++;
        }
        return new Origins(knowledgeDrill, wrongReview, world);
    }

    /**
     * 「Attempt 的冻结 target KnowledgePoint 仍在当前 Selected Books 范围内」的 SQL 判定。
     *
     * <p>与 {@link LearnerProgressStore#selectedMemberships} 同源，但按 target 单点判定；
     * 用 EXISTS 而不是 JOIN，避免多本文集共享同一 KnowledgePoint 时把行数放大。</p>
     */
    private static String scopedTargetExists() {
        return """
                EXISTS (
                    SELECT 1
                      FROM learner_selected_book selected
                      JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                      JOIN question_bank_knowledge membership ON membership.bank_id=b.id
                      JOIN global_knowledge_point k ON k.id=membership.knowledge_point_id AND k.status='active'
                     WHERE selected.learner_id=a.learner_id
                       AND membership.knowledge_point_id=a.target_knowledge_point_id
                       AND """ + " " + TrainableKnowledge.exists("k") + """
                )
                """.trim();
    }

    private List<Daily> daily(String learnerId, Instant now, int days) {
        return activity.activityAt(learnerId, now, days).daily().stream()
                .map(day -> new Daily(day.date(), day.effectiveAttempts(), day.distinctKnowledgePoints())).toList();
    }

    public record StatisticsView(int days, Instant generatedAt, Summary summary,
                                 List<Daily> daily, List<BookMastery> books) {}
    public record Summary(int gradedAttempts, int activeStudyDays, int distinctKnowledgePoints,
                          int knowledgeDrillAttempts, int wrongReviewAttempts, int worldAttempts,
                          int correct, int partial, int wrong, int revealedOnly) {}
    public record Daily(LocalDate date, int gradedAttempts, int distinctKnowledgePoints) {}
    public record BookMastery(String bookId, String name, double masteryProgress, int knowledgePointCount) {}

    private record Origins(int knowledgeDrill, int wrongReview, int world) {}
}

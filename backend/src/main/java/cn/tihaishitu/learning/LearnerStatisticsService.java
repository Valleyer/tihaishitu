package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@Service
public class LearnerStatisticsService {
    private final JdbcTemplate jdbc;
    private final LearnerProgressService progress;
    private final Clock clock = Clock.systemUTC();

    public LearnerStatisticsService(JdbcTemplate jdbc, LearnerProgressService progress) {
        this.jdbc = jdbc;
        this.progress = progress;
    }

    public StatisticsView current(int days) {
        if (!List.of(7, 30, 90).contains(days)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "统计范围只支持 7、30 或 90 天。");
        }
        String learnerId = LearnerContext.learnerId();
        Instant now = clock.instant();
        LocalDate today = now.atZone(LearnerQuestionMasteryStore.BUSINESS_ZONE).toLocalDate();
        Instant from = today.minusDays(days - 1L).atStartOfDay(LearnerQuestionMasteryStore.BUSINESS_ZONE).toInstant();
        record Activity(Instant answeredAt, String pointId, String intent, boolean world, String assessment) {}
        List<Activity> activities = jdbc.query("""
                SELECT a.answered_at,a.target_knowledge_point_id,p.intent,a.world_id,a.assessment
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                  LEFT JOIN learner_practice_session p ON p.id=a.practice_session_id
                 WHERE a.learner_id=? AND a.status='graded' AND a.answered_at>=? AND a.answered_at<=?
                   AND q.status='published' AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                 ORDER BY a.answered_at,a.id
                """, (row, index) -> new Activity(row.getTimestamp("answered_at").toInstant(),
                row.getString("target_knowledge_point_id"), row.getString("intent"),
                row.getString("world_id") != null, row.getString("assessment")),
                learnerId, Timestamp.from(from), Timestamp.from(now));

        Set<LocalDate> activeDates = new HashSet<>();
        Set<String> points = new HashSet<>();
        Map<LocalDate, List<Activity>> byDate = new HashMap<>();
        int knowledgeDrill = 0, wrongReview = 0, worldAttempts = 0, correct = 0, partial = 0, wrong = 0;
        for (Activity activity : activities) {
            LocalDate date = activity.answeredAt().atZone(LearnerQuestionMasteryStore.BUSINESS_ZONE).toLocalDate();
            activeDates.add(date); points.add(activity.pointId());
            byDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(activity);
            if ("knowledge_drill".equals(activity.intent())) knowledgeDrill++;
            // 错题单题重做与错题快练统一计入错题练习作答统计。
            if ("wrong_review".equals(activity.intent()) || "wrong_drill".equals(activity.intent())) wrongReview++;
            if (activity.world()) worldAttempts++;
            if ("correct".equals(activity.assessment())) correct++;
            else if ("partial".equals(activity.assessment())) partial++;
            else if ("wrong".equals(activity.assessment())) wrong++;
        }
        Summary summary = new Summary(activities.size(), activeDates.size(), points.size(), knowledgeDrill,
                wrongReview, worldAttempts, correct, partial, wrong);
        List<Daily> daily = new ArrayList<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            List<Activity> rows = byDate.getOrDefault(date, List.of());
            daily.add(new Daily(date, rows.size(), (int) rows.stream().map(Activity::pointId).distinct().count()));
        }

        List<BookMastery> books = progress.progressAt(learnerId, now).books().stream()
                .map(book -> new BookMastery(book.bookId(), book.name(), book.masteryProgress(),
                        book.totalKnowledgePoints())).toList();
        return new StatisticsView(days, now, summary, daily, books);
    }

    public record StatisticsView(int days, Instant generatedAt, Summary summary,
                                 List<Daily> daily, List<BookMastery> books) {}
    public record Summary(int gradedAttempts, int activeStudyDays, int distinctKnowledgePoints,
                          int knowledgeDrillAttempts, int wrongReviewAttempts, int worldAttempts,
                          int correct, int partial, int wrong) {}
    public record Daily(LocalDate date, int gradedAttempts, int distinctKnowledgePoints) {}
    public record BookMastery(String bookId, String name, double masteryProgress, int knowledgePointCount) {}
}

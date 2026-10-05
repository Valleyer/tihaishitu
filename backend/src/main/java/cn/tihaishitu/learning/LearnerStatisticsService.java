package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant from = today.minusDays(days - 1L).atStartOfDay(ZoneOffset.UTC).toInstant();
        Summary summary = jdbc.query("""
                SELECT COUNT(*) graded_attempts,
                       COUNT(DISTINCT CAST(a.answered_at AS DATE)) active_days,
                       COUNT(DISTINCT a.target_knowledge_point_id) distinct_points,
                       SUM(CASE WHEN p.intent='knowledge_drill' THEN 1 ELSE 0 END) knowledge_drill,
                       SUM(CASE WHEN p.intent='wrong_review' THEN 1 ELSE 0 END) wrong_review,
                       SUM(CASE WHEN a.world_id IS NOT NULL THEN 1 ELSE 0 END) world_attempts,
                       SUM(CASE WHEN a.assessment='correct' THEN 1 ELSE 0 END) correct_count,
                       SUM(CASE WHEN a.assessment='partial' THEN 1 ELSE 0 END) partial_count,
                       SUM(CASE WHEN a.assessment='wrong' THEN 1 ELSE 0 END) wrong_count
                  FROM study_attempt a
                  LEFT JOIN learner_practice_session p ON p.id=a.practice_session_id
                 WHERE a.learner_id=? AND a.status='graded' AND a.answered_at>=? AND a.answered_at<=?
                """, (row, index) -> new Summary(row.getInt("graded_attempts"), row.getInt("active_days"),
                row.getInt("distinct_points"), row.getInt("knowledge_drill"), row.getInt("wrong_review"),
                row.getInt("world_attempts"), row.getInt("correct_count"), row.getInt("partial_count"),
                row.getInt("wrong_count")), learnerId, Timestamp.from(from), Timestamp.from(now)).get(0);

        Map<LocalDate, Daily> byDate = new HashMap<>();
        jdbc.query("""
                SELECT CAST(answered_at AS DATE) study_date, COUNT(*) graded_attempts,
                       COUNT(DISTINCT target_knowledge_point_id) distinct_points
                  FROM study_attempt
                 WHERE learner_id=? AND status='graded' AND answered_at>=? AND answered_at<=?
                 GROUP BY CAST(answered_at AS DATE)
                """, row -> {
            LocalDate date = row.getDate("study_date").toLocalDate();
            byDate.put(date, new Daily(date, row.getInt("graded_attempts"), row.getInt("distinct_points")));
        }, learnerId, Timestamp.from(from), Timestamp.from(now));
        List<Daily> daily = new ArrayList<>();
        for (int offset = days - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            daily.add(byDate.getOrDefault(date, new Daily(date, 0, 0)));
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

package cn.tihaishitu.game;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
public class LearnerQuestionExposureStore {
    public record Exposure(String questionId, int exposureCount, Instant lastExposedAt) {}

    private final JdbcTemplate jdbc;

    public LearnerQuestionExposureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Exposure> findForQuestions(String learnerId, Collection<String> questionIds) {
        if (learnerId == null || learnerId.isBlank() || questionIds.isEmpty()) return Map.of();
        Map<String, Exposure> exposures = new LinkedHashMap<>();
        Object[] args = new Object[questionIds.size() + 1];
        args[0] = learnerId;
        int index = 1;
        for (String questionId : questionIds) args[index++] = questionId;
        jdbc.query("""
                SELECT question_id, COUNT(*) AS exposure_count, MAX(created_at) AS last_exposed_at
                  FROM study_attempt
                 WHERE learner_id = ? AND question_id IN (%s)
                 GROUP BY question_id
                """.formatted(placeholders(questionIds.size())), result -> {
            String questionId = result.getString("question_id");
            Timestamp lastExposedAt = result.getTimestamp("last_exposed_at");
            exposures.put(questionId, new Exposure(questionId, result.getInt("exposure_count"),
                    lastExposedAt.toInstant()));
        }, args);
        return Map.copyOf(exposures);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}

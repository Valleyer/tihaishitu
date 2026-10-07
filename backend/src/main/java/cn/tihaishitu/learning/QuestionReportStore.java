package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class QuestionReportStore {
    public record AttemptFact(String learnerId, String questionId) {}
    private final JdbcTemplate jdbc;
    public QuestionReportStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AttemptFact formalAttempt(String attemptId) {
        List<AttemptFact> rows = jdbc.query("""
                SELECT a.learner_id,a.question_id FROM study_attempt a
                JOIN question_resource q ON q.id=a.question_id
                WHERE a.id=? AND a.learner_id IS NOT NULL AND q.status='published' AND q.parent_question_id IS NULL
                  AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                """, (rs, row) -> new AttemptFact(rs.getString(1), rs.getString(2)), attemptId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String create(String learnerId, String questionId, String attemptId, String reason, String comment) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_report(id,learner_id,question_id,attempt_id,reason,comment,status)
                VALUES (?,?,?,?,?,?,'open')
                """, id, learnerId, questionId, attemptId, reason, comment);
        return id;
    }
}

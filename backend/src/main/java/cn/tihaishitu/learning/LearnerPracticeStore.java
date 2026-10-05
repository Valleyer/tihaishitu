package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class LearnerPracticeStore {
    public record Session(String id, String learnerId, String intent, String targetKnowledgePointId,
                          String sourceQuestionId, String currentAttemptId, String status, long revision,
                          Instant createdAt, Instant updatedAt, Instant endedAt) {}
    public record WrongQuestion(String questionId, String targetKnowledgePointId, String knowledgePointName,
                                String subject, String chapter, String summary, Instant lastGradedAt) {}

    private final JdbcTemplate jdbc;

    public LearnerPracticeStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(String id, String learnerId, String intent, String targetId,
                       String sourceQuestionId, Set<String> scope) {
        jdbc.update("""
                INSERT INTO learner_practice_session(
                    id,learner_id,intent,target_knowledge_point_id,source_question_id,status,revision)
                VALUES (?,?,?,?,?,'active',1)
                """, id, learnerId, intent, targetId, sourceQuestionId);
        for (String pointId : scope) {
            jdbc.update("""
                    INSERT INTO learner_practice_scope(session_id,knowledge_point_id) VALUES (?,?)
                    """, id, pointId);
        }
    }

    public Optional<Session> find(String id, String learnerId) {
        return sessions("WHERE id=? AND learner_id=?", id, learnerId).stream().findFirst();
    }

    public Session lock(String id, String learnerId) {
        return sessions("WHERE id=? AND learner_id=? FOR UPDATE", id, learnerId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("专项练习不存在或不属于当前学习者。"));
    }

    public Set<String> scope(String id) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT knowledge_point_id FROM learner_practice_scope
                 WHERE session_id=? ORDER BY knowledge_point_id
                """, (rs, row) -> rs.getString(1), id));
    }

    public Set<String> seenQuestions(String id) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT question_id FROM study_attempt
                 WHERE practice_session_id=? ORDER BY question_id
                """, (rs, row) -> rs.getString(1), id));
    }

    public void setCurrentAttempt(String id, String learnerId, String attemptId) {
        int changed = jdbc.update("""
                UPDATE learner_practice_session
                   SET current_attempt_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND learner_id=? AND status='active'
                """, attemptId, id, learnerId);
        if (changed != 1) throw new IllegalStateException("专项练习状态已经变化。");
    }

    public void end(String id, String learnerId, Instant now) {
        int changed = jdbc.update("""
                UPDATE learner_practice_session
                   SET status='ended',ended_at=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND learner_id=? AND status='active'
                """, Timestamp.from(now), id, learnerId);
        if (changed != 1) throw new IllegalStateException("专项练习已经结束。");
    }

    public List<WrongQuestion> wrongQuestions(String learnerId) {
        return jdbc.query("""
                SELECT a.question_id,a.target_knowledge_point_id,k.name knowledge_name,
                       q.subject_name,k.chapter_name,q.content_markdown,a.answered_at
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                  JOIN global_knowledge_point k ON k.id=a.target_knowledge_point_id
                 WHERE a.learner_id=? AND a.status='graded' AND a.assessment IN ('wrong','partial')
                   AND q.status='published'
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                   AND k.status='active'
                   AND """ + " " + TrainableKnowledge.exists("k") + """
                   AND NOT EXISTS (
                       SELECT 1 FROM study_attempt newer
                        WHERE newer.learner_id=a.learner_id AND newer.question_id=a.question_id
                          AND newer.status='graded'
                          AND (newer.answered_at>a.answered_at
                               OR (newer.answered_at=a.answered_at AND newer.id>a.id))
                   )
                 ORDER BY a.answered_at DESC,a.id DESC
                """, (rs, row) -> new WrongQuestion(rs.getString("question_id"),
                rs.getString("target_knowledge_point_id"), rs.getString("knowledge_name"),
                rs.getString("subject_name"), rs.getString("chapter_name"),
                summary(rs.getString("content_markdown")), rs.getTimestamp("answered_at").toInstant()), learnerId);
    }

    private List<Session> sessions(String predicate, Object... args) {
        return jdbc.query("""
                SELECT id,learner_id,intent,target_knowledge_point_id,source_question_id,current_attempt_id,
                       status,revision,created_at,updated_at,ended_at
                  FROM learner_practice_session %s
                """.formatted(predicate), (rs, row) -> new Session(rs.getString("id"),
                rs.getString("learner_id"), rs.getString("intent"), rs.getString("target_knowledge_point_id"),
                rs.getString("source_question_id"), rs.getString("current_attempt_id"), rs.getString("status"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant()), args);
    }

    private static String summary(String value) {
        String plain = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= 120 ? plain : plain.substring(0, 117) + "...";
    }
}

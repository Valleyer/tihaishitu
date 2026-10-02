package cn.tihaishitu.game;

import cn.tihaishitu.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class QuestionAttemptStore {
    public record Snapshot(String id, String gameId, String questionId, JsonNode question, JsonNode standard,
                           String status, String gradingMode, String gradingSource, String assessment) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public QuestionAttemptStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void create(String id, String gameId, String questionId, JsonNode question, JsonNode standard) {
        create(id, gameId, questionId, question, standard, question.path("gradingMode").asText("auto"));
    }

    public void create(String id, String gameId, String questionId, JsonNode question, JsonNode standard,
                       String gradingMode) {
        jdbc.update("""
                INSERT INTO study_attempt(id, game_id, question_id, question_snapshot_json, standard_answer_json,
                                          status, grading_mode)
                VALUES (?, ?, ?, ?, ?, 'active', ?)
                """, id, gameId, questionId, json(question), json(standard), gradingMode);
    }

    public Snapshot find(String id, String gameId) {
        List<Snapshot> values = jdbc.query("""
                SELECT id, game_id, question_id, question_snapshot_json, standard_answer_json, status,
                       grading_mode, grading_source, assessment
                  FROM study_attempt WHERE id = ? AND game_id = ?
                """, (result, row) -> new Snapshot(result.getString("id"), result.getString("game_id"),
                result.getString("question_id"), read(result.getString("question_snapshot_json")),
                read(result.getString("standard_answer_json")), result.getString("status"),
                result.getString("grading_mode"), result.getString("grading_source"),
                result.getString("assessment")), id, gameId);
        if (values.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "这道题已经失效，请重新载入当前进度。");
        return values.get(0);
    }

    public boolean recordAnswer(Snapshot snapshot, JsonNode submitted, boolean correct) {
        if (!"auto".equals(snapshot.gradingMode()))
            throw new ApiException(HttpStatus.CONFLICT, "这是一道自评题，请先查看参考解答后自评。");
        if (!"active".equals(snapshot.status())) return false;
        String assessment = correct ? "correct" : "wrong";
        int changed = jdbc.update("""
                UPDATE study_attempt SET status = 'graded', answered_at = ?, grading_source = 'automatic', assessment = ?
                 WHERE id = ? AND status = 'active'
                """, Timestamp.from(Instant.now()), assessment, snapshot.id());
        if (changed == 0) return false;
        jdbc.update("""
                INSERT INTO answer_record(id, game_id, attempt_id, question_id, submitted_answer_json, correct,
                                          grading_source, assessment)
                VALUES (?, ?, ?, ?, ?, ?, 'automatic', ?)
                """, UUID.randomUUID().toString(), snapshot.gameId(), snapshot.id(), snapshot.questionId(),
                json(submitted), correct, assessment);
        return true;
    }

    public boolean reveal(Snapshot snapshot) {
        if (!"self_assessment".equals(snapshot.gradingMode()))
            throw new ApiException(HttpStatus.CONFLICT, "自动判题无需单独查看参考解答。");
        if ("revealed".equals(snapshot.status()) || "graded".equals(snapshot.status())) return false;
        return jdbc.update("""
                UPDATE study_attempt SET status = 'revealed', answer_revealed_at = ?
                 WHERE id = ? AND status = 'active'
                """, Timestamp.from(Instant.now()), snapshot.id()) > 0;
    }

    public boolean recordSelfAssessment(Snapshot snapshot, String assessment) {
        if (!"self_assessment".equals(snapshot.gradingMode()))
            throw new ApiException(HttpStatus.CONFLICT, "这不是一道自评题。");
        if (!java.util.Set.of("correct", "partial", "wrong").contains(assessment))
            throw new ApiException(HttpStatus.BAD_REQUEST, "自评结果不合法。");
        if (!"revealed".equals(snapshot.status())) return false;
        boolean correct = "correct".equals(assessment);
        int changed = jdbc.update("""
                UPDATE study_attempt SET status = 'graded', answered_at = ?, grading_source = 'self', assessment = ?
                 WHERE id = ? AND status = 'revealed'
                """, Timestamp.from(Instant.now()), assessment, snapshot.id());
        if (changed == 0) return false;
        jdbc.update("""
                INSERT INTO answer_record(id, game_id, attempt_id, question_id, submitted_answer_json, correct,
                                          grading_source, assessment)
                VALUES (?, ?, ?, ?, ?, ?, 'self', ?)
                """, UUID.randomUUID().toString(), snapshot.gameId(), snapshot.id(), snapshot.questionId(),
                json(assessment), correct, assessment);
        return true;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("答题快照无法序列化。", error);
        }
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库中的答题快照损坏。", error);
        }
    }
}

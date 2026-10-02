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
    public record Snapshot(String id, String gameId, String questionId, JsonNode question, JsonNode standard, String status) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public QuestionAttemptStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void create(String id, String gameId, String questionId, JsonNode question, JsonNode standard) {
        jdbc.update("""
                INSERT INTO study_attempt(id, game_id, question_id, question_snapshot_json, standard_answer_json, status)
                VALUES (?, ?, ?, ?, ?, 'active')
                """, id, gameId, questionId, json(question), json(standard));
    }

    public Snapshot find(String id, String gameId) {
        List<Snapshot> values = jdbc.query("""
                SELECT id, game_id, question_id, question_snapshot_json, standard_answer_json, status
                  FROM study_attempt WHERE id = ? AND game_id = ?
                """, (result, row) -> new Snapshot(result.getString("id"), result.getString("game_id"),
                result.getString("question_id"), read(result.getString("question_snapshot_json")),
                read(result.getString("standard_answer_json")), result.getString("status")), id, gameId);
        if (values.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "这道题已经失效，请重新载入当前进度。");
        return values.get(0);
    }

    public boolean recordAnswer(Snapshot snapshot, JsonNode submitted, boolean correct) {
        if (!"active".equals(snapshot.status())) return false;
        int changed = jdbc.update("UPDATE study_attempt SET status = 'answered', answered_at = ? WHERE id = ? AND status = 'active'",
                Timestamp.from(Instant.now()), snapshot.id());
        if (changed == 0) return false;
        jdbc.update("""
                INSERT INTO answer_record(id, game_id, attempt_id, question_id, submitted_answer_json, correct)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), snapshot.gameId(), snapshot.id(), snapshot.questionId(),
                json(submitted), correct);
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

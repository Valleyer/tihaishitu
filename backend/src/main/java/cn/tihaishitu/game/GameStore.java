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

@Repository
public class GameStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GameStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(JsonNode game) {
        jdbc.update(
                """
                INSERT INTO game_save(
                    id, player_name, player_title, answer_total, payload_json,
                    revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                game.path("id").asText(), game.path("player").path("name").asText(),
                game.path("player").path("title").asText(), game.path("records").size(), json(game),
                game.path("revision").asLong(), Timestamp.from(Instant.parse(game.path("createdAt").asText())),
                Timestamp.from(Instant.parse(game.path("updatedAt").asText()))
        );
    }

    public JsonNode find(String id) {
        List<String> values = jdbc.query(
                "SELECT payload_json FROM game_save WHERE id = ?",
                (result, row) -> result.getString("payload_json"),
                id
        );
        if (values.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "没有找到这份存档。");
        return read(values.get(0));
    }

    public void delete(String id) {
        if (jdbc.update("DELETE FROM game_save WHERE id = ?", id) == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "没有找到这份存档。");
    }

    public List<SaveSummaryDto> summaries() {
        return jdbc.query(
                """
                SELECT id, player_name, player_title, answer_total, updated_at
                  FROM game_save
                 ORDER BY updated_at DESC
                """,
                (result, row) -> new SaveSummaryDto(
                        result.getString("id"), result.getString("player_name"),
                        result.getString("player_title"), result.getInt("answer_total"),
                        result.getTimestamp("updated_at").toInstant()
                )
        );
    }

    public String latestId() {
        List<String> ids = jdbc.query(
                "SELECT id FROM game_save ORDER BY updated_at DESC",
                (result, row) -> result.getString("id")
        );
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("存档无法序列化。", error);
        }
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库中的存档不是合法 JSON。", error);
        }
    }
}

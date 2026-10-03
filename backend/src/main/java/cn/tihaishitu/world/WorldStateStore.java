package cn.tihaishitu.world;

import cn.tihaishitu.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class WorldStateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public WorldStateStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public ObjectNode find(String learnerId, String worldId) {
        List<ObjectNode> values = jdbc.query("SELECT state_json, revision FROM learner_world_state WHERE learner_id = ? AND world_id = ?",
                (result, row) -> {
                    ObjectNode state = read(result.getString("state_json"));
                    state.put("id", worldId);
                    state.put("worldId", worldId);
                    state.put("revision", result.getLong("revision"));
                    return state;
                }, learnerId, worldId);
        if (values.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "尚未进入这个世界。");
        return values.get(0);
    }

    public boolean exists(String learnerId, String worldId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM learner_world_state WHERE learner_id = ? AND world_id = ?",
                Integer.class, learnerId, worldId);
        return count != null && count > 0;
    }

    public String updatedAt(String learnerId, String worldId) {
        List<String> values = jdbc.query("SELECT updated_at FROM learner_world_state WHERE learner_id = ? AND world_id = ?",
                (result, row) -> result.getTimestamp("updated_at").toInstant().toString(), learnerId, worldId);
        return values.isEmpty() ? null : values.get(0);
    }

    public void insert(String learnerId, String worldId, ObjectNode state) {
        ObjectNode stored = state.deepCopy();
        stored.remove(List.of("id", "worldId", "revision"));
        try {
            jdbc.update("INSERT INTO learner_world_state(learner_id, world_id, state_json, revision) VALUES (?, ?, ?, 0)",
                    learnerId, worldId, json(stored));
        } catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "这个世界已经初始化，不能重复创建。");
        }
    }

    public void save(String learnerId, String worldId, ObjectNode state) {
        long expected = state.path("revision").asLong();
        state.put("updatedAt", java.time.Instant.now().toString());
        ObjectNode stored = state.deepCopy();
        stored.remove(List.of("id", "worldId", "revision"));
        int changed = jdbc.update("""
                UPDATE learner_world_state SET state_json = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE learner_id = ? AND world_id = ? AND revision = ?
                """, json(stored), learnerId, worldId, expected);
        if (changed == 0) throw new ApiException(HttpStatus.CONFLICT, "世界进度已在其他页面更新，请刷新后重试。");
        state.put("revision", expected + 1);
    }

    private String json(ObjectNode state) {
        try { return mapper.writeValueAsString(state); }
        catch (JsonProcessingException error) { throw new IllegalStateException("世界状态无法序列化。", error); }
    }
    private ObjectNode read(String value) {
        try { return (ObjectNode) mapper.readTree(value); }
        catch (JsonProcessingException | ClassCastException error) { throw new IllegalStateException("世界状态数据损坏。", error); }
    }
}

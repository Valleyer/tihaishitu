package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GameService {
    private final GameStore store;
    private final GameFactory factory;
    private final ObjectMapper mapper;

    public GameService(GameStore store, GameFactory factory, ObjectMapper mapper) {
        this.store = store;
        this.factory = factory;
        this.mapper = mapper;
    }

    @Transactional
    public JsonNode create(NewGameRequest request) {
        JsonNode game = factory.create(request);
        store.insert(game);
        return game;
    }

    public JsonNode find(String id) {
        return factory.hydrate(store.findObject(id));
    }

    @Transactional
    public void delete(String id) {
        store.delete(id);
    }

    public List<SaveSummaryDto> summaries() {
        return store.summaries();
    }

    public String latestId() {
        return store.latestId();
    }

    public String exportSave(String id) {
        return store.find(id).toString();
    }

    @Transactional
    public JsonNode importSave(String json) {
        try {
            ObjectNode game = (ObjectNode) mapper.readTree(json);
            if (!game.path("player").isObject() || !game.path("config").isObject())
                throw new cn.tihaishitu.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "存档结构不完整。");
            String now = java.time.Instant.now().toString();
            game.put("id", java.util.UUID.randomUUID().toString());
            game.put("createdAt", now); game.put("updatedAt", now); game.put("revision", 0);
            factory.hydrate(game);
            store.insert(game);
            return game;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException error) {
            throw new cn.tihaishitu.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "存档 JSON 格式不正确。");
        }
    }
}

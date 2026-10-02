package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GameService {
    private final GameStore store;
    private final GameFactory factory;

    public GameService(GameStore store, GameFactory factory) {
        this.store = store;
        this.factory = factory;
    }

    @Transactional
    public JsonNode create(NewGameRequest request) {
        JsonNode game = factory.create(request);
        store.insert(game);
        return game;
    }

    public JsonNode find(String id) {
        return store.find(id);
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
}

package cn.tihaishitu.game;

import cn.tihaishitu.catalog.CatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class GameFactory {
    private final ObjectMapper objectMapper;
    private final CatalogService catalog;
    private final JsonNode gameDesign;
    private final JsonNode chapters;
    private final JsonNode characters;
    private final JsonNode adventureDesign;
    private final JsonNode exams;

    public GameFactory(ObjectMapper objectMapper, CatalogService catalog) throws IOException {
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.gameDesign = resource("content/game.json");
        this.chapters = resource("content/chapters.json");
        this.characters = resource("content/characters.json");
        this.adventureDesign = resource("content/adventure.json");
        this.exams = resource("content/exams.json");
    }

    public ObjectNode create(NewGameRequest request) {
        String now = Instant.now().toString();
        List<String> bankIds = request.bankIds().isEmpty()
                ? catalog.findAll().stream().filter(bank -> bank.enabled() && bank.weight() > 0)
                        .map(bank -> bank.id()).toList()
                : request.bankIds();

        ObjectNode config = objectMapper.createObjectNode();
        config.put("name", request.name().trim());
        config.put("gender", defaultText(request.gender(), "不设定"));
        config.put("origin", defaultText(request.origin(), "寒门读书人"));
        config.set("bankIds", objectMapper.valueToTree(bankIds));
        config.set("weights", objectMapper.valueToTree(defaultWeights(request.weights())));
        config.put("pace", valid(request.pace(), List.of("slow", "normal"), "normal"));
        config.put("difficulty", valid(request.difficulty(), List.of("gentle", "standard"), "gentle"));

        ObjectNode player = objectMapper.createObjectNode();
        player.put("name", request.name().trim());
        player.put("gender", config.path("gender").asText());
        player.put("origin", config.path("origin").asText());
        player.put("knowledge", 0);
        player.put("reputation", 0);
        player.put("coins", originCoins(config.path("origin").asText()));
        player.put("title", chapters.path(0).path("playerTitle").asText("寒门书生"));

        ObjectNode game = objectMapper.createObjectNode();
        game.put("id", UUID.randomUUID().toString());
        game.put("version", 2);
        game.put("createdAt", now);
        game.put("updatedAt", now);
        game.set("config", config);
        game.set("player", player);
        game.set("npcs", characters.deepCopy());
        game.set("records", objectMapper.createArrayNode());
        game.set("learning", objectMapper.createObjectNode());
        game.set("journal", initialJournal());
        game.set("flags", objectMapper.createArrayNode());
        game.putNull("attempt");
        game.putNull("event");
        game.set("notes", objectMapper.createObjectNode());
        game.put("chapter", 0);
        game.put("revision", 0);
        game.set("adventure", initialAdventure());
        return game;
    }

    /**
     * 配置新增人物或科举后，旧存档在读取时原地补齐容器。
     * 已有关系和考试成绩保持不变；这里只追加缺失项。
     */
    public ObjectNode hydrate(ObjectNode game) {
        if (!game.path("npcs").isArray()) game.set("npcs", objectMapper.createArrayNode());
        ArrayNode savedNpcs = (ArrayNode) game.path("npcs");
        characters.forEach(character -> {
            boolean exists = false;
            for (JsonNode saved : savedNpcs)
                if (saved.path("id").asText().equals(character.path("id").asText())) { exists = true; break; }
            if (!exists) savedNpcs.add(character.deepCopy());
        });
        if (!game.path("adventure").isObject()) game.set("adventure", initialAdventure());
        ObjectNode adventure = (ObjectNode) game.path("adventure");
        if (!adventure.path("exams").isObject()) adventure.set("exams", objectMapper.createObjectNode());
        ObjectNode records = (ObjectNode) adventure.path("exams");
        exams.forEach(exam -> {
            String id = exam.path("id").asText();
            if (!records.path(id).isObject()) records.set(id, blankExamRecord());
        });
        return game;
    }

    private ObjectNode blankExamRecord() {
        ObjectNode record = objectMapper.createObjectNode();
        record.put("status", "unregistered");
        record.put("attempts", 0);
        record.put("best", 0);
        record.put("lastScore", 0);
        return record;
    }

    private ObjectNode initialAdventure() {
        ObjectNode adventure = objectMapper.createObjectNode();
        String start = adventureDesign.path("startLocation").asText("old-school");
        adventure.put("version", 6);
        adventure.put("locationId", start);
        adventure.set("visited", objectMapper.valueToTree(List.of(start)));
        ObjectNode attributes = objectMapper.createObjectNode();
        adventureDesign.path("attributes").forEach(attribute -> attributes.put(attribute.path("id").asText(), 0));
        adventure.set("attributes", attributes);
        adventure.set("inventory", objectMapper.createObjectNode());
        adventure.set("equipped", objectMapper.createObjectNode());
        adventure.set("best", objectMapper.createObjectNode());
        adventure.set("clears", objectMapper.createObjectNode());
        adventure.set("rewardClaims", objectMapper.createArrayNode());
        adventure.set("conversations", objectMapper.createObjectNode());
        adventure.set("seenEncounters", objectMapper.createArrayNode());
        adventure.putNull("encounter");
        adventure.putNull("run");
        ObjectNode examRecords = objectMapper.createObjectNode();
        exams.forEach(exam -> {
            examRecords.set(exam.path("id").asText(), blankExamRecord());
        });
        adventure.set("exams", examRecords);
        return adventure;
    }

    private ArrayNode initialJournal() {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("id", UUID.randomUUID().toString());
        entry.put("day", 0);
        entry.put("title", gameDesign.path("initialJournalTitle").asText("负笈青溪"));
        entry.put("text", gameDesign.path("initialJournal").asText("今日来到青溪。"));
        entry.put("kind", "story");
        ArrayNode result = objectMapper.createArrayNode();
        result.add(entry);
        return result;
    }

    private Map<String, Integer> defaultWeights(Map<String, Integer> requested) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        gameDesign.path("defaultWeights").fields().forEachRemaining(entry -> weights.put(entry.getKey(), entry.getValue().asInt()));
        weights.putAll(requested);
        return weights;
    }

    private int originCoins(String origin) {
        for (JsonNode item : gameDesign.path("origins"))
            if (origin.equals(item.path("name").asText())) return item.path("coins").asInt(24);
        return 24;
    }

    private JsonNode resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(input);
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String valid(String value, List<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }
}

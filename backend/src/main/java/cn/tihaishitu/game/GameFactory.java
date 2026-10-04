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
    private final GameContent content;
    private final JsonNode gameDesign;
    private final JsonNode chapters;
    private final JsonNode characters;
    private final JsonNode adventureDesign;
    private final JsonNode exams;

    public GameFactory(ObjectMapper objectMapper, CatalogService catalog, GameContent content) throws IOException {
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.content = content;
        this.gameDesign = resource("content/game.json");
        this.chapters = resource("content/chapters.json");
        this.characters = resource("content/characters.json");
        this.adventureDesign = resource("content/adventure.json");
        this.exams = resource("content/exams.json");
    }

    public ObjectNode create(NewGameRequest request) {
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

        ObjectNode game = createState(request.name().trim(), config.path("gender").asText(), config.path("origin").asText());
        game.set("config", config);
        return game;
    }

    public ObjectNode createAncientOfficialState(String name, String gender, String origin) {
        String normalizedName = name.trim();
        String normalizedGender = defaultText(gender, "不设定");
        String normalizedOrigin = defaultText(origin, "寒门读书人");
        return createState(normalizedName, normalizedGender, normalizedOrigin);
    }

    private ObjectNode createState(String name, String gender, String origin) {
        String now = Instant.now().toString();

        ObjectNode player = objectMapper.createObjectNode();
        player.put("name", name);
        player.put("gender", gender);
        player.put("origin", origin);
        player.put("knowledge", 0);
        player.put("reputation", 0);
        player.put("coins", originCoins(origin));
        player.put("title", chapters.path(0).path("playerTitle").asText("寒门书生"));

        ObjectNode game = objectMapper.createObjectNode();
        game.put("id", UUID.randomUUID().toString());
        game.put("version", 2);
        game.put("createdAt", now);
        game.put("updatedAt", now);
        game.set("player", player);
        game.set("npcs", canonicalCharacters());
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
        savedNpcs.forEach(saved -> normalizeFavorability((ObjectNode) saved));
        characters.forEach(character -> {
            boolean exists = false;
            for (JsonNode saved : savedNpcs) {
                if (!saved.path("id").asText().equals(character.path("id").asText())) continue;
                normalizeFavorability((ObjectNode) saved);
                exists = true;
                break;
            }
            if (!exists) {
                ObjectNode added = character.deepCopy();
                normalizeFavorability(added);
                savedNpcs.add(added);
            }
        });
        if (!game.path("adventure").isObject()) game.set("adventure", initialAdventure());
        ObjectNode adventure = (ObjectNode) game.path("adventure");
        if (!adventure.path("exams").isObject()) adventure.set("exams", objectMapper.createObjectNode());
        ObjectNode records = (ObjectNode) adventure.path("exams");
        exams.forEach(exam -> {
            String id = exam.path("id").asText();
            if (!records.path(id).isObject()) records.set(id, blankExamRecord());
        });
        if (!adventure.path("clears").isObject()) adventure.set("clears", objectMapper.createObjectNode());
        ObjectNode clears = (ObjectNode) adventure.path("clears");
        List<String> completedIds = new java.util.ArrayList<>();
        clears.fields().forEachRemaining(entry -> {
            if (entry.getValue().asInt() > 0) completedIds.add(entry.getKey());
        });
        completedIds.forEach(id -> content.activity(id).ifPresent(activity -> {
            if ("task".equals(activity.path("activityMode").asText())) clears.put(id, 1);
        }));
        if (adventure.path("run").isObject()) {
            ObjectNode run = (ObjectNode) adventure.path("run");
            run.put("entryCost", run.path("entryCost").asInt());
            run.put("costCommitted", run.path("costCommitted").asBoolean());
            run.put("costRefunded", run.path("costRefunded").asBoolean());
            content.activity(run.path("definition").path("id").asText()).ifPresent(activity -> {
                ObjectNode definition = (ObjectNode) run.path("definition");
                for (String key : List.of("activityMode", "completionReward", "successDialogue", "failureDialogue"))
                    if (activity.has(key)) definition.set(key, activity.path(key).deepCopy());
            });
        }
        return game;
    }

    private ArrayNode canonicalCharacters() {
        ArrayNode result = objectMapper.createArrayNode();
        characters.forEach(character -> {
            ObjectNode npc = character.deepCopy();
            normalizeFavorability(npc);
            result.add(npc);
        });
        return result;
    }

    /** Older saves used two relationship tracks; the stronger one becomes the single durable value. */
    private static void normalizeFavorability(ObjectNode npc) {
        int value = npc.has("favorability")
                ? npc.path("favorability").asInt()
                : Math.max(npc.path("affinity").asInt(), npc.path("trust").asInt());
        npc.put("favorability", Math.max(0, Math.min(100, value)));
        npc.remove(List.of("affinity", "trust"));
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

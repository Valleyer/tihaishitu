package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
public class GameContent {
    private final ObjectMapper mapper;
    private final ArrayNode activities;
    private final ArrayNode locations;
    private final ArrayNode exams;
    private final ArrayNode companions;
    private final ArrayNode items;
    private final ArrayNode events;

    public GameContent(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        activities = mapper.createArrayNode();
        resource("content/activities.json").forEach(item -> activities.add(normalizeActivity((ObjectNode) item)));
        resource("content/activities-v7.json").forEach(item -> activities.add(normalizeActivity((ObjectNode) item)));
        resource("content/activities-v8.json").forEach(item -> activities.add(normalizeActivity((ObjectNode) item)));
        locations = (ArrayNode) resource("content/maps.json").path("locations");
        exams = (ArrayNode) resource("content/exams.json");
        companions = mapper.createArrayNode();
        resource("content/companions.json").forEach(companions::add);
        resource("content/companions-v8.json").forEach(companions::add);
        items = mapper.createArrayNode();
        resource("content/items.json").forEach(items::add);
        resource("content/items-v7.json").forEach(items::add);
        resource("content/items-v8.json").forEach(items::add);
        events = (ArrayNode) resource("content/events.json");
    }

    public Optional<ObjectNode> activity(String id) { return find(activities, id); }
    public Optional<ObjectNode> location(String id) { return find(locations, id); }
    public Optional<ObjectNode> exam(String id) { return find(exams, id); }
    public Optional<ObjectNode> examForActivity(String activityId) {
        for (JsonNode item : exams)
            if (activityId.equals(item.path("activityId").asText()) || activityId.equals(item.path("preparationActivityId").asText()))
                return Optional.of((ObjectNode) item);
        return Optional.empty();
    }
    public Optional<ObjectNode> companion(String npcId) {
        for (JsonNode item : companions) if (npcId.equals(item.path("npcId").asText())) return Optional.of((ObjectNode) item);
        return Optional.empty();
    }
    public Optional<ObjectNode> item(String id) { return find(items, id); }
    public Optional<ObjectNode> event(String id) { return find(events, id); }

    private ObjectNode normalizeActivity(ObjectNode source) {
        ObjectNode value = source.deepCopy();
        boolean main = "exam".equals(value.path("kind").asText()) || "main".equals(value.path("quest").asText());
        value.put("rounds", main ? 10 : 5);
        value.put("passScore", main ? 100 : 60);
        ArrayNode original = (ArrayNode) value.path("tiers");
        ArrayNode tiers = mapper.createArrayNode();
        JsonNode zero = tier(original, 0, false);
        JsonNode base = tier(original, 60, false);
        JsonNode perfect = tier(original, 100, true);
        if (!zero.isMissingNode()) tiers.add(zero.deepCopy());
        if (!main && !base.isMissingNode()) tiers.add(base.deepCopy());
        if (!perfect.isMissingNode()) {
            ObjectNode perfectCopy = perfect.deepCopy();
            ObjectNode mergedFirst = mapper.createObjectNode();
            for (JsonNode tier : original) if (tier.path("minScore").asInt() > (main ? 0 : 60))
                mergeReward(mergedFirst, tier.path("firstRewards"));
            if (!mergedFirst.isEmpty()) perfectCopy.set("firstRewards", mergedFirst);
            tiers.add(perfectCopy);
        }
        value.set("tiers", tiers);
        return value;
    }

    private void mergeReward(ObjectNode target, JsonNode source) {
        if (!source.isObject()) return;
        for (String key : java.util.List.of("knowledge", "coins", "reputation"))
            if (source.path(key).asInt() != 0) target.put(key, target.path(key).asInt() + source.path(key).asInt());
        for (String key : java.util.List.of("attributes", "affinity", "trust", "items"))
            source.path(key).fields().forEachRemaining(entry -> {
                ObjectNode values = target.with(key);
                values.put(entry.getKey(), values.path(entry.getKey()).asInt() + entry.getValue().asInt());
            });
        if (source.path("flags").isArray()) {
            ArrayNode flags = target.withArray("flags");
            source.path("flags").forEach(flag -> {
                boolean exists = false;
                for (JsonNode old : flags) if (old.asText().equals(flag.asText())) { exists = true; break; }
                if (!exists) flags.add(flag.asText());
            });
        }
        if (source.hasNonNull("title")) target.put("title", source.path("title").asText());
    }

    private JsonNode tier(ArrayNode values, int score, boolean highest) {
        JsonNode selected = mapper.missingNode();
        for (JsonNode item : values) {
            int current = item.path("minScore").asInt();
            if (current == score) return item;
            if (highest && (selected.isMissingNode() || current > selected.path("minScore").asInt())) selected = item;
            if (!highest && current > 0 && current <= score &&
                    (selected.isMissingNode() || current > selected.path("minScore").asInt())) selected = item;
        }
        return selected;
    }

    private Optional<ObjectNode> find(ArrayNode values, String id) {
        for (JsonNode item : values) if (id.equals(item.path("id").asText())) return Optional.of((ObjectNode) item);
        return Optional.empty();
    }

    private JsonNode resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(input);
        }
    }
}

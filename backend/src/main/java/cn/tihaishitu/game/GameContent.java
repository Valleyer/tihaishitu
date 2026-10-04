package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Loads legacy content through the current task/activity and favorability contract. */
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
        locations = mapper.createArrayNode();
        resource("content/maps.json").path("locations").forEach(item -> locations.add(normalizeRequirementsContainer((ObjectNode) item)));
        exams = mapper.createArrayNode();
        resource("content/exams.json").forEach(item -> exams.add(normalizeRequirementsContainer((ObjectNode) item)));
        companions = mapper.createArrayNode();
        resource("content/companions.json").forEach(item -> companions.add(normalizeCompanion((ObjectNode) item)));
        resource("content/companions-v8.json").forEach(item -> companions.add(normalizeCompanion((ObjectNode) item)));
        items = mapper.createArrayNode();
        resource("content/items.json").forEach(item -> items.add(normalizeRewardContainer((ObjectNode) item, "use")));
        resource("content/items-v7.json").forEach(item -> items.add(normalizeRewardContainer((ObjectNode) item, "use")));
        resource("content/items-v8.json").forEach(item -> items.add(normalizeRewardContainer((ObjectNode) item, "use")));
        events = mapper.createArrayNode();
        resource("content/events.json").forEach(item -> events.add(normalizeEvent((ObjectNode) item)));
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

    ObjectNode normalizeActivityForCompatibility(ObjectNode source) { return normalizeActivity(source); }
    ObjectNode normalizeRewardForCompatibility(JsonNode source) { return normalizeReward(source); }

    private ObjectNode normalizeActivity(ObjectNode source) {
        ObjectNode value = normalizeRequirementsContainer(source);
        boolean main = "exam".equals(value.path("kind").asText()) || "main".equals(value.path("quest").asText());
        String explicitMode = value.path("activityMode").asText();
        boolean task = "task".equals(explicitMode) || (!"repeatable".equals(explicitMode)
                && !value.path("repeatable").asBoolean()
                && (main || "side".equals(value.path("quest").asText())
                || List.of("dungeon", "story").contains(value.path("kind").asText())));
        value.put("activityMode", task ? "task" : "repeatable");
        value.put("repeatable", !task);
        value.put("rounds", main ? 10 : 5);
        value.put("passScore", main ? 100 : 60);

        ArrayNode original = (ArrayNode) value.path("tiers");
        if (!task) {
            ArrayNode normalized = mapper.createArrayNode();
            JsonNode zero = tier(original, 0, false);
            JsonNode base = tier(original, 60, false);
            JsonNode perfect = tier(original, 100, true);
            if (!zero.isMissingNode()) normalized.add(normalizeTier((ObjectNode) zero));
            if (!base.isMissingNode()) normalized.add(normalizeTier((ObjectNode) base));
            if (!perfect.isMissingNode()) {
                ObjectNode perfectCopy = normalizeTier((ObjectNode) perfect);
                ObjectNode mergedFirst = mapper.createObjectNode();
                for (JsonNode item : original) if (item.path("minScore").asInt() > 60)
                    mergeRewardSum(mergedFirst, normalizeReward(item.path("firstRewards")));
                if (!mergedFirst.isEmpty()) perfectCopy.set("firstRewards", mergedFirst);
                else perfectCopy.remove("firstRewards");
                normalized.add(perfectCopy);
            }
            value.set("tiers", normalized);
            return value;
        }

        ObjectNode completionReward = mapper.createObjectNode();
        JsonNode failure = tier(original, 0, false);
        JsonNode highest = tier(original, 100, true);
        int titleScore = Integer.MIN_VALUE;
        String highestTitle = null;
        for (JsonNode item : original) if (item.path("minScore").asInt() > 0) {
            mergeRewardMax(completionReward, normalizeReward(item.path("rewards")));
            mergeRewardMax(completionReward, normalizeReward(item.path("firstRewards")));
            for (String rewardKey : List.of("rewards", "firstRewards"))
                if (item.path(rewardKey).hasNonNull("title") && item.path("minScore").asInt() >= titleScore) {
                    titleScore = item.path("minScore").asInt();
                    highestTitle = item.path(rewardKey).path("title").asText();
                }
        }
        if (highestTitle != null) completionReward.put("title", highestTitle);
        value.set("completionReward", completionReward);
        value.put("successDialogue", highest.path("dialogue").asText("此事已经办妥。"));
        value.put("failureDialogue", failure.path("dialogue").asText("尚未完成，准备好后可以重新开始。"));
        ArrayNode tiers = mapper.createArrayNode();
        ObjectNode failed = mapper.createObjectNode();
        failed.put("minScore", 0);
        failed.put("label", main ? "尚未完成" : "继续努力");
        failed.set("rewards", mapper.createObjectNode());
        failed.put("dialogue", value.path("failureDialogue").asText());
        tiers.add(failed);
        ObjectNode completed = mapper.createObjectNode();
        completed.put("minScore", value.path("passScore").asInt());
        completed.put("label", main ? ("exam".equals(value.path("kind").asText()) ? "全对取中" : "全对完成") : "任务完成");
        completed.set("rewards", completionReward.deepCopy());
        completed.put("dialogue", value.path("successDialogue").asText());
        tiers.add(completed);
        value.set("tiers", tiers);
        return value;
    }

    private ObjectNode normalizeTier(ObjectNode source) {
        ObjectNode tier = source.deepCopy();
        tier.set("rewards", normalizeReward(source.path("rewards")));
        if (source.has("firstRewards")) tier.set("firstRewards", normalizeReward(source.path("firstRewards")));
        return tier;
    }

    private ObjectNode normalizeCompanion(ObjectNode source) {
        ObjectNode value = source.deepCopy();
        for (String key : List.of("greetings", "topics")) value.path(key).forEach(item -> {
            ObjectNode entry = (ObjectNode) item;
            entry.put("minFavorability", entry.path("minFavorability").asInt(entry.path("minAffinity").asInt()));
            entry.remove("minAffinity");
        });
        value.path("milestones").forEach(item -> {
            ObjectNode milestone = (ObjectNode) item;
            milestone.put("favorability", milestone.path("favorability").asInt(milestone.path("affinity").asInt()));
            milestone.remove("affinity");
            milestone.set("reward", normalizeReward(milestone.path("reward")));
        });
        return value;
    }

    private ObjectNode normalizeEvent(ObjectNode source) {
        ObjectNode value = source.deepCopy();
        value.path("options").forEach(option -> {
            ObjectNode object = (ObjectNode) option;
            object.set("effects", normalizeReward(object.path("effects")));
        });
        return value;
    }

    private ObjectNode normalizeRewardContainer(ObjectNode source, String key) {
        ObjectNode value = source.deepCopy();
        if (value.has(key)) value.set(key, normalizeReward(value.path(key)));
        return value;
    }

    private ObjectNode normalizeRequirementsContainer(ObjectNode source) {
        ObjectNode value = source.deepCopy();
        if (value.has("requirements")) value.set("requirements", normalizeRequirements(value.path("requirements")));
        return value;
    }

    private ObjectNode normalizeRequirements(JsonNode source) {
        ObjectNode value = source.isObject() ? source.deepCopy() : mapper.createObjectNode();
        ObjectNode favorability = value.with("favorability");
        mergeMapMax(favorability, source.path("affinity"));
        mergeMapMax(favorability, source.path("trust"));
        if (favorability.isEmpty()) value.remove("favorability");
        value.remove(List.of("affinity", "trust"));
        return value;
    }

    private ObjectNode normalizeReward(JsonNode source) {
        ObjectNode value = source.isObject() ? source.deepCopy() : mapper.createObjectNode();
        ObjectNode favorability = value.with("favorability");
        mergeMapMax(favorability, source.path("affinity"));
        mergeMapMax(favorability, source.path("trust"));
        if (favorability.isEmpty()) value.remove("favorability");
        value.remove(List.of("affinity", "trust"));
        return value;
    }

    /** Task reward collapse deliberately takes maxima so legacy tiers cannot stack into an inflated payout. */
    private void mergeRewardMax(ObjectNode target, JsonNode source) {
        if (!source.isObject()) return;
        for (String key : List.of("knowledge", "coins", "reputation"))
            if (source.has(key)) target.put(key, Math.max(target.path(key).asInt(), source.path(key).asInt()));
        for (String key : List.of("attributes", "favorability", "items"))
            mergeMapMax(target.with(key), source.path(key));
        if (source.path("flags").isArray()) {
            ArrayNode flags = target.withArray("flags");
            source.path("flags").forEach(flag -> addUnique(flags, flag.asText()));
        }
        if (source.hasNonNull("title")) target.put("title", source.path("title").asText());
        for (String key : List.of("attributes", "favorability", "items"))
            if (target.path(key).isObject() && target.path(key).isEmpty()) target.remove(key);
    }

    private void mergeRewardSum(ObjectNode target, JsonNode source) {
        if (!source.isObject()) return;
        for (String key : List.of("knowledge", "coins", "reputation"))
            if (source.has(key)) target.put(key, target.path(key).asInt() + source.path(key).asInt());
        for (String key : List.of("attributes", "favorability", "items"))
            source.path(key).fields().forEachRemaining(entry -> {
                ObjectNode values = target.with(key);
                values.put(entry.getKey(), values.path(entry.getKey()).asInt() + entry.getValue().asInt());
            });
        if (source.path("flags").isArray()) {
            ArrayNode flags = target.withArray("flags");
            source.path("flags").forEach(flag -> addUnique(flags, flag.asText()));
        }
        if (source.hasNonNull("title")) target.put("title", source.path("title").asText());
    }

    private static void mergeMapMax(ObjectNode target, JsonNode source) {
        source.fields().forEachRemaining(entry ->
                target.put(entry.getKey(), Math.max(target.path(entry.getKey()).asInt(), entry.getValue().asInt())));
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

    private static void addUnique(ArrayNode values, String value) {
        for (JsonNode item : values) if (value.equals(item.asText())) return;
        values.add(value);
    }

    private JsonNode resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(input);
        }
    }
}

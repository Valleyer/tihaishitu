package cn.tihaishitu.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QuestionAttemptVariantService {
    public record AttemptVariant(JsonNode question, JsonNode standard) {}

    private final ObjectMapper mapper;

    public QuestionAttemptVariantService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AttemptVariant create(JsonNode question, JsonNode standard, JsonNode previousQuestion) {
        return create(question, standard, previousQuestion, standard);
    }

    public AttemptVariant create(JsonNode question, JsonNode standard, JsonNode previousQuestion,
                                 JsonNode previousStandard) {
        ObjectNode snapshot = question.deepCopy();
        JsonNode options = snapshot.path("options");
        String presentation = snapshot.path("presentationType").asText(snapshot.path("type").asText());
        if (!Set.of("single_choice", "multiple_choice", "true_false").contains(presentation)
                || !options.isObject() || options.size() < 2) {
            return new AttemptVariant(snapshot, standard.deepCopy());
        }

        List<String> sourceKeys = new ArrayList<>();
        options.fieldNames().forEachRemaining(sourceKeys::add);
        List<String> previousTexts = optionTexts(previousQuestion);
        Set<String> previousKeys = answerKeys(previousStandard);
        List<List<String>> orders = candidateOrders(sourceKeys);
        AttemptVariant changedOrder = null;
        for (List<String> order : orders) {
            AttemptVariant candidate = applyOrder(snapshot, standard, order);
            if (!previousTexts.isEmpty() && optionTexts(candidate.question()).equals(previousTexts)) continue;
            if (changedOrder == null) changedOrder = candidate;
            if (!answerKeys(candidate.standard()).equals(previousKeys)) return candidate;
        }
        return changedOrder == null ? new AttemptVariant(snapshot, standard.deepCopy()) : changedOrder;
    }

    AttemptVariant applyOrder(JsonNode question, JsonNode standard, List<String> sourceKeysInDisplayOrder) {
        ObjectNode snapshot = question.deepCopy();
        JsonNode sourceOptions = question.path("options");
        List<String> visibleKeys = new ArrayList<>();
        sourceOptions.fieldNames().forEachRemaining(visibleKeys::add);
        if (sourceKeysInDisplayOrder.size() != visibleKeys.size()
                || !new HashSet<>(sourceKeysInDisplayOrder).equals(new HashSet<>(visibleKeys))) {
            throw new IllegalArgumentException("选项排列必须完整包含原选项。");
        }

        ObjectNode remappedOptions = mapper.createObjectNode();
        java.util.LinkedHashMap<String, String> oldToNew = new java.util.LinkedHashMap<>();
        for (int index = 0; index < visibleKeys.size(); index++) {
            String newKey = visibleKeys.get(index);
            String oldKey = sourceKeysInDisplayOrder.get(index);
            remappedOptions.set(newKey, sourceOptions.get(oldKey));
            oldToNew.put(oldKey, newKey);
        }
        snapshot.set("options", remappedOptions);
        JsonNode remappedStandard = remapStandard(standard, oldToNew, visibleKeys);
        if (snapshot.has("answer")) snapshot.set("answer", remappedStandard.deepCopy());
        return new AttemptVariant(snapshot, remappedStandard);
    }

    private JsonNode remapStandard(JsonNode standard, Map<String, String> oldToNew, List<String> visibleKeys) {
        if (standard.isTextual()) {
            String remapped = oldToNew.get(standard.asText());
            return remapped == null ? standard.deepCopy() : mapper.getNodeFactory().textNode(remapped);
        }
        if (standard.isArray()) {
            Set<String> remapped = new HashSet<>();
            standard.forEach(value -> remapped.add(oldToNew.getOrDefault(value.asText(), value.asText())));
            ArrayNode result = mapper.createArrayNode();
            visibleKeys.stream().filter(remapped::contains).forEach(result::add);
            return result.size() == remapped.size() ? result : standard.deepCopy();
        }
        if (standard.isBoolean()) {
            String oldKey = standard.asBoolean() ? "true" : "false";
            String newKey = oldToNew.get(oldKey);
            return newKey == null ? standard.deepCopy()
                    : mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(newKey));
        }
        return standard.deepCopy();
    }

    private static List<String> optionTexts(JsonNode question) {
        if (question == null || !question.path("options").isObject()) return List.of();
        List<String> values = new ArrayList<>();
        question.path("options").elements().forEachRemaining(value -> values.add(value.asText()));
        return values;
    }

    private static Set<String> answerKeys(JsonNode standard) {
        if (standard == null) return Set.of();
        Set<String> values = new HashSet<>();
        if (standard.isArray()) standard.forEach(value -> values.add(value.asText()));
        else if (standard.isTextual()) values.add(standard.asText());
        else if (standard.isBoolean()) values.add(standard.asBoolean() ? "true" : "false");
        return values;
    }

    private static List<List<String>> candidateOrders(List<String> sourceKeys) {
        List<List<String>> orders = new ArrayList<>();
        for (int attempt = 0; attempt < 32; attempt++) {
            List<String> shuffled = new ArrayList<>(sourceKeys);
            Collections.shuffle(shuffled);
            if (!shuffled.equals(sourceKeys) && !orders.contains(shuffled)) orders.add(shuffled);
        }
        for (int shift = 1; shift < sourceKeys.size(); shift++) {
            List<String> rotated = new ArrayList<>(sourceKeys);
            Collections.rotate(rotated, shift);
            if (!orders.contains(rotated)) orders.add(rotated);
        }
        for (int left = 0; left < sourceKeys.size(); left++) {
            for (int right = left + 1; right < sourceKeys.size(); right++) {
                List<String> swapped = new ArrayList<>(sourceKeys);
                Collections.swap(swapped, left, right);
                if (!orders.contains(swapped)) orders.add(swapped);
            }
        }
        return orders;
    }
}

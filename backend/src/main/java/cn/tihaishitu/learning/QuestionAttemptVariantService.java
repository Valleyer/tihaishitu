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
        ObjectNode snapshot = question.deepCopy();
        JsonNode options = snapshot.path("options");
        String presentation = snapshot.path("presentationType").asText(snapshot.path("type").asText());
        if (!Set.of("single_choice", "multiple_choice").contains(presentation)
                || !options.isObject() || options.size() < 2) {
            return new AttemptVariant(snapshot, standard.deepCopy());
        }

        List<String> sourceKeys = new ArrayList<>();
        options.fieldNames().forEachRemaining(sourceKeys::add);
        List<String> sourceOrder = new ArrayList<>(sourceKeys);
        List<String> previousTexts = optionTexts(previousQuestion);
        for (int attempt = 0; attempt < 5; attempt++) {
            Collections.shuffle(sourceOrder);
            AttemptVariant candidate = applyOrder(snapshot, standard, sourceOrder);
            if (previousTexts.isEmpty() || !optionTexts(candidate.question()).equals(previousTexts)) return candidate;
        }
        Collections.rotate(sourceOrder, 1);
        AttemptVariant candidate = applyOrder(snapshot, standard, sourceOrder);
        if (!previousTexts.isEmpty() && optionTexts(candidate.question()).equals(previousTexts)) {
            sourceOrder = new ArrayList<>(sourceKeys);
            Collections.rotate(sourceOrder, 1);
            candidate = applyOrder(snapshot, standard, sourceOrder);
        }
        return candidate;
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
        return standard.deepCopy();
    }

    private static List<String> optionTexts(JsonNode question) {
        if (question == null || !question.path("options").isObject()) return List.of();
        List<String> values = new ArrayList<>();
        question.path("options").elements().forEachRemaining(value -> values.add(value.asText()));
        return values;
    }
}

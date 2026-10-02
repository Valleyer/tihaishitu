package cn.tihaishitu.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QuestionDto(
        String id,
        String subject,
        String category,
        String chapter,
        String type,
        String question,
        Map<String, String> options,
        JsonNode answer,
        String explanation,
        List<String> aliases,
        List<String> keywords,
        int difficulty,
        int frequency,
        List<String> tags,
        List<String> knowledgePointIds,
        boolean enabled
) {
    public QuestionDto {
        options = options == null ? Map.of() : new LinkedHashMap<>(options);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        tags = tags == null ? List.of() : List.copyOf(tags);
        knowledgePointIds = knowledgePointIds == null ? List.of() : List.copyOf(knowledgePointIds);
    }
}

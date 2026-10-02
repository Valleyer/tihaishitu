package cn.tihaishitu.catalog;

import java.util.List;

public record KnowledgePointDto(
        String id,
        String name,
        String subject,
        String category,
        String description,
        String explanation,
        String parentId,
        List<String> prerequisites,
        List<String> tags
) {
    public KnowledgePointDto {
        prerequisites = prerequisites == null ? List.of() : List.copyOf(prerequisites);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}

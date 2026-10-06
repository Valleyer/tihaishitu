package cn.tihaishitu.catalog;

public record QuestionBankManifest(
        String id,
        String name,
        String description,
        boolean enabled,
        int weight,
        long revision,
        int questionCount,
        int knowledgePointCount,
        int totalKnowledgePointCount
) {}

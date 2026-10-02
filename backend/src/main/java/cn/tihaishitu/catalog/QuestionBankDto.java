package cn.tihaishitu.catalog;

import java.util.List;

public record QuestionBankDto(
        String id,
        String name,
        String description,
        List<KnowledgePointDto> knowledgePoints,
        List<QuestionDto> questions,
        boolean enabled,
        int weight
) {
    public QuestionBankDto {
        knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}

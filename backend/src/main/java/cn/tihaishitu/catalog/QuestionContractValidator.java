package cn.tihaishitu.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class QuestionContractValidator {
    public static final String BLANK_ERROR = "万境书院不支持填空题；原填空题必须在生成阶段转换为单选题或多选题。";
    public record Option(String key, String text, boolean correct, int sortOrder) {
        public Option(String key, String text, boolean correct) {
            this(key, text, correct, 0);
        }
    }

    private QuestionContractValidator() {}

    public static Optional<String> validateFormal(String type, String presentation, String grading,
                                                  String analysis, List<Option> options) {
        if ("blank".equals(type)) return Optional.of(BLANK_ERROR);
        String expectedPresentation;
        String expectedGrading;
        switch (type == null ? "" : type) {
            case "single_choice" -> { expectedPresentation = "single_choice"; expectedGrading = "auto"; }
            case "multiple_choice" -> { expectedPresentation = "multiple_choice"; expectedGrading = "auto"; }
            case "true_false" -> { expectedPresentation = "true_false"; expectedGrading = "auto"; }
            case "solution" -> { expectedPresentation = "self_assessment"; expectedGrading = "self_assessment"; }
            default -> { return Optional.of("题型不受支持。"); }
        }
        if (!expectedPresentation.equals(presentation) || !expectedGrading.equals(grading)) {
            return Optional.of("题型、展示类型与判题模式不匹配。");
        }
        List<Option> values = options == null ? List.of() : options;
        if ("solution".equals(type)) {
            if (!values.isEmpty()) return Optional.of("综合题不能提供客观题选项。");
            if (analysis == null || analysis.isBlank()) return Optional.of("综合题完整解析不能为空。");
            return Optional.empty();
        }
        if (values.size() < 2 || values.size() > 6) return Optional.of("客观题必须提供 2–6 个选项。");
        Set<String> keys = new HashSet<>();
        Set<String> correct = new HashSet<>();
        for (Option option : values) {
            if (option == null || blank(option.key()) || blank(option.text()) || !keys.add(option.key().trim())) {
                return Optional.of("选项键和值不能为空，且选项键不能重复。");
            }
            if (option.correct()) correct.add(option.key().trim());
        }
        if ("single_choice".equals(type)) {
            if (correct.size() != 1) return Optional.of("单选题必须且只能有一个正确选项。");
            return Optional.empty();
        }
        if ("multiple_choice".equals(type)) {
            if (correct.size() < 2) return Optional.of("多选题至少需要两个正确选项。");
            return Optional.empty();
        }
        if (!keys.equals(Set.of("true", "false"))) return Optional.of("判断题选项键必须固定为 true 和 false。");
        if (correct.size() != 1) return Optional.of("判断题必须且只能有一个正确选项。");
        return Optional.empty();
    }

    /** 仅供仍以 standardAnswer 工作的 Remedial / 旧文集兼容路径使用。 */
    public static Optional<String> validateLegacy(String type, String presentation, String grading,
                                                  JsonNode answer, String analysis, List<Option> options) {
        Optional<String> formal = validateFormal(type, presentation, grading,
                "solution".equals(type) && answer != null && answer.isTextual() ? answer.asText() : analysis, options);
        if (formal.isPresent()) return formal;
        if ("solution".equals(type)) {
            return answer == null || !answer.isTextual() || answer.asText().isBlank()
                    ? Optional.of("综合题参考答案必须是非空 Markdown 字符串。") : Optional.empty();
        }
        Set<String> correct = new HashSet<>();
        (options == null ? List.<Option>of() : options).stream().filter(Option::correct)
                .forEach(option -> correct.add(option.key().trim()));
        if ("single_choice".equals(type)) {
            return answer == null || !answer.isTextual() || !correct.equals(Set.of(answer.asText()))
                    ? Optional.of("单选题标准答案必须与唯一正确选项一致。") : Optional.empty();
        }
        if ("multiple_choice".equals(type)) {
            if (answer == null || !answer.isArray()) return Optional.of("多选题标准答案必须是字符串数组。");
            Set<String> selected = new HashSet<>();
            for (JsonNode value : answer) {
                if (!value.isTextual() || !selected.add(value.asText())) {
                    return Optional.of("多选题标准答案包含非字符串或重复选项键。");
                }
            }
            return selected.equals(correct) ? Optional.empty()
                    : Optional.of("多选题标准答案必须与全部正确选项一致。");
        }
        if (answer == null || !answer.isBoolean()) return Optional.of("判断题标准答案必须是 boolean。");
        String answerKey = answer.asBoolean() ? "true" : "false";
        return correct.equals(Set.of(answerKey)) ? Optional.empty()
                : Optional.of("判断题标准答案必须与正确选项一致。");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}

package cn.tihaishitu.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Formal Question 的运行时答案只从 option.correct 派生，不读取题库答案字段。 */
@Component
public class QuestionAnswerDeriver {
    public record Option(String key, boolean correct, int sortOrder) {}

    private final ObjectMapper mapper;

    public QuestionAnswerDeriver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode derive(String questionType, List<Option> options) {
        List<Option> correct = (options == null ? List.<Option>of() : options).stream()
                .filter(Option::correct)
                .sorted(Comparator.comparingInt(Option::sortOrder).thenComparing(Option::key))
                .toList();
        return switch (questionType == null ? "" : questionType) {
            case "single_choice" -> mapper.getNodeFactory().textNode(requireOne(correct).key());
            case "multiple_choice" -> {
                ArrayNode answer = mapper.createArrayNode();
                correct.forEach(option -> answer.add(option.key()));
                yield answer;
            }
            case "true_false" -> mapper.getNodeFactory().booleanNode(
                    requireBooleanKey(requireOne(correct).key()));
            case "solution" -> mapper.nullNode();
            default -> throw new IllegalArgumentException("题型不受支持：" + questionType);
        };
    }

    private static Option requireOne(List<Option> correct) {
        if (correct.size() != 1) throw new IllegalArgumentException("题目必须且只能有一个正确选项。");
        return correct.get(0);
    }

    /**
     * 判断题正确选项键必须显式是 true 或 false。
     * 禁止使用 Boolean.parseBoolean 之类的宽松解析：非法键会被静默判成 false，造成误判。
     */
    private static boolean requireBooleanKey(String key) {
        String normalized = key == null ? "" : key.strip();
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        throw new IllegalArgumentException(
                "判断题正确选项键必须是 true 或 false，实际为：" + (key == null ? "null" : key));
    }
}

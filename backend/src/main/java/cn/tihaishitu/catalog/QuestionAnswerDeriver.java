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
                    Boolean.parseBoolean(requireOne(correct).key()));
            case "solution" -> mapper.nullNode();
            default -> throw new IllegalArgumentException("题型不受支持：" + questionType);
        };
    }

    private static Option requireOne(List<Option> correct) {
        if (correct.size() != 1) throw new IllegalArgumentException("题目必须且只能有一个正确选项。");
        return correct.get(0);
    }
}

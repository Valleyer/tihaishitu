package cn.tihaishitu.learning;

import cn.tihaishitu.game.QuestionGradingPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionAttemptVariantServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestionAttemptVariantService variants = new QuestionAttemptVariantService(mapper);

    @Test
    void remapsSingleAndMultipleChoiceAnswersToTheNewVisibleKeys() throws Exception {
        JsonNode single = question("single_choice");
        var singleVariant = variants.applyOrder(single, mapper.readTree("\"B\""), List.of("D", "A", "C", "B"));
        assertThat(optionTexts(singleVariant.question())).containsExactly("d", "a", "c", "b");
        assertThat(singleVariant.standard().asText()).isEqualTo("D");
        assertThat(QuestionGradingPolicy.matches(singleVariant.standard(), mapper.readTree("\"D\""))).isTrue();
        assertThat(QuestionGradingPolicy.matches(singleVariant.standard(), mapper.readTree("\"B\""))).isFalse();

        JsonNode multiple = question("multiple_choice");
        var multipleVariant = variants.applyOrder(multiple, mapper.readTree("[\"A\",\"C\"]"),
                List.of("D", "C", "B", "A"));
        assertThat(optionTexts(multipleVariant.question())).containsExactly("d", "c", "b", "a");
        assertThat(multipleVariant.standard()).isEqualTo(mapper.readTree("[\"B\",\"D\"]"));
        assertThat(QuestionGradingPolicy.matches(multipleVariant.standard(), mapper.readTree("[\"D\",\"B\"]"))).isTrue();
    }

    @Test
    void remapsTrueFalseBooleanWithItsVisibleTextAndAvoidsPreviousChoiceOrder() throws Exception {
        JsonNode trueFalse = mapper.readTree("""
                {"presentationType":"true_false","options":{"true":"正确","false":"错误"},"answer":true}
                """);
        var swapped = variants.applyOrder(trueFalse, mapper.readTree("true"), List.of("false", "true"));
        assertThat(optionTexts(swapped.question())).containsExactly("错误", "正确");
        assertThat(swapped.standard()).isEqualTo(mapper.readTree("false"));
        assertThat(QuestionGradingPolicy.matches(swapped.standard(), mapper.readTree("false"))).isTrue();

        JsonNode choice = question("single_choice");
        var reshuffled = variants.create(choice, mapper.readTree("\"B\""), choice);
        assertThat(optionTexts(reshuffled.question())).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(optionTexts(reshuffled.question())).isNotEqualTo(optionTexts(choice));
        String correctText = reshuffled.question().path("options").path(reshuffled.standard().asText()).asText();
        assertThat(correctText).isEqualTo("b");
    }

    private JsonNode question(String presentationType) throws Exception {
        return mapper.readTree("""
                {"presentationType":"%s","options":{"A":"a","B":"b","C":"c","D":"d"},"answer":"B"}
                """.formatted(presentationType));
    }

    private List<String> optionTexts(JsonNode question) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        question.path("options").elements().forEachRemaining(value -> values.add(value.asText()));
        return values;
    }
}

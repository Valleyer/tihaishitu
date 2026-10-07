package cn.tihaishitu.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionAnswerDeriverTest {
    private final QuestionAnswerDeriver deriver = new QuestionAnswerDeriver(new ObjectMapper());

    @Test
    void derivesAllFormalQuestionAnswersFromOrderedOptionFacts() throws Exception {
        assertThat(deriver.derive("single_choice", List.of(
                option("A", false, 0), option("C", true, 1))).asText()).isEqualTo("C");
        assertThat(deriver.derive("multiple_choice", List.of(
                option("C", true, 2), option("A", true, 0), option("B", false, 1))))
                .isEqualTo(new ObjectMapper().readTree("[\"A\",\"C\"]"));
        assertThat(deriver.derive("true_false", List.of(
                option("true", false, 0), option("false", true, 1))).asBoolean()).isFalse();
        assertThat(deriver.derive("solution", List.of()).isNull()).isTrue();
    }

    @Test
    void formalValidatorUsesOptionFactsAndRequiresCompleteSolutionAnalysis() {
        assertThat(QuestionContractValidator.validateFormal("single_choice", "single_choice", "auto", "解析",
                List.of(new QuestionContractValidator.Option("A", "甲", true),
                        new QuestionContractValidator.Option("B", "乙", true))))
                .contains("单选题必须且只能有一个正确选项。");
        assertThat(QuestionContractValidator.validateFormal("solution", "self_assessment", "self_assessment", " ", List.of()))
                .contains("综合题完整解析不能为空。");
    }

    private static QuestionAnswerDeriver.Option option(String key, boolean correct, int order) {
        return new QuestionAnswerDeriver.Option(key, correct, order);
    }
}

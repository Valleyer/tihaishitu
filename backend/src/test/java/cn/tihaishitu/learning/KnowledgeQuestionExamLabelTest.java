package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真题展示标签是动态生成的：年份事实来源是 exam_year，科目事实来源是 subject_name。
 * 不新增 tag 表，也不把年份建成 KnowledgePoint。
 */
class KnowledgeQuestionExamLabelTest {
    @Test
    void generatesStableLabelsForSupportedSubjects() {
        assertThat(KnowledgeQuestionExamLabel.generate("数学一", 2022)).isEqualTo("2022年考研数学一真题");
        assertThat(KnowledgeQuestionExamLabel.generate("数学一", 2021)).isEqualTo("2021年考研数学一真题");
        assertThat(KnowledgeQuestionExamLabel.generate("408计算机学科专业基础", 2024)).isEqualTo("2024年408考研真题");
        assertThat(KnowledgeQuestionExamLabel.generate("408", 2024)).isEqualTo("2024年408考研真题");
    }

    @Test
    void unknownSubjectOrMissingYearNeverProducesAHalfLabel() {
        assertThat(KnowledgeQuestionExamLabel.generate("数学一", null)).isNull();
        assertThat(KnowledgeQuestionExamLabel.generate("未知科目", 2022)).isNull();
        assertThat(KnowledgeQuestionExamLabel.generate(null, 2022)).isNull();
        assertThat(KnowledgeQuestionExamLabel.generate("   ", 2022)).isNull();
    }

    @Test
    void titleDegradesGracefullyWhenQuestionNumberIsMissing() {
        assertThat(KnowledgeQuestionExamLabel.title("数学一", 2022, "3")).isEqualTo("2022年考研数学一真题 · 第3题");
        assertThat(KnowledgeQuestionExamLabel.title("408计算机学科专业基础", 2024, "16"))
                .isEqualTo("2024年408考研真题 · 第16题");
        assertThat(KnowledgeQuestionExamLabel.title("数学一", 2022, null)).isEqualTo("2022年考研数学一真题");
        assertThat(KnowledgeQuestionExamLabel.title("未知科目", 2022, "3")).isEqualTo("第3题");
        assertThat(KnowledgeQuestionExamLabel.title("未知科目", null, null)).isNull();
    }
}

package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题号显示格式化：UI 一律使用 displayQuestionNumber，原始 questionNumber 作为数据事实保留。
 */
class QuestionNumberFormatterTest {
    @Test
    void stripsTheSameYearPrefix() {
        assertThat(QuestionNumberFormatter.display("2014-1", 2014)).isEqualTo("1");
        assertThat(QuestionNumberFormatter.display("2022-16", 2022)).isEqualTo("16");
    }

    @Test
    void keepsPlainNumbersAndOtherFormatsUntouched() {
        assertThat(QuestionNumberFormatter.display("3", 2022)).isEqualTo("3");
        assertThat(QuestionNumberFormatter.display("16", null)).isEqualTo("16");
        // 不同年份的前缀不能剥离，否则会误删合法题号。
        assertThat(QuestionNumberFormatter.display("2021-3", 2022)).isEqualTo("2021-3");
        // 无法安全解析的题号原样保留，不猜。
        assertThat(QuestionNumberFormatter.display("A-3", 2022)).isEqualTo("A-3");
        assertThat(QuestionNumberFormatter.display("3(1)", 2022)).isEqualTo("3(1)");
        assertThat(QuestionNumberFormatter.display("21A", 2022)).isEqualTo("21A");
    }

    @Test
    void emptyValuesAndDanglingPrefixDegradeSafely() {
        assertThat(QuestionNumberFormatter.display(null, 2022)).isNull();
        assertThat(QuestionNumberFormatter.display("   ", 2022)).isNull();
        assertThat(QuestionNumberFormatter.display(" 2022-3 ", 2022)).isEqualTo("3");
        // "2014-" 没有可用题号，保留原始值而不是显示空。
        assertThat(QuestionNumberFormatter.display("2014-", 2014)).isEqualTo("2014-");
    }
}

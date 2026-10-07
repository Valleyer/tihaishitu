package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * “年份-题号”结构化搜索的解析规则。
 *
 * <p>解析器是 Management 与全平台题库共用的唯一实现，因此这里锁定行为：
 * {@code 2020-7} / {@code 2020 - 7} / {@code 2020—7} 都解析为
 * {@code year=2020, number="7"}，普通关键词保持 broad search。</p>
 */
class QuestionSearchQueryTest {
    @Test
    void parsesYearNumberWithEverySupportedSeparator() {
        for (String query : List.of("2020-7", "2020 - 7", "2020—7", "2020–7", "2020‑7", "  2020-7  ")) {
            QuestionSearchQuery parsed = QuestionSearchQuery.parse(query);
            assertThat(parsed.structured()).as(query).isTrue();
            assertThat(parsed.year()).as(query).isEqualTo(2020);
            assertThat(parsed.number()).as(query).isEqualTo("7");
            assertThat(parsed.yearNumberLiteral()).as(query).isEqualTo("2020-7");
            assertThat(parsed.parts()).as(query).containsExactly("2020", "7");
        }
    }

    @Test
    void keepsCompoundQuestionNumbers() {
        QuestionSearchQuery parsed = QuestionSearchQuery.parse("2020-3(1)");
        assertThat(parsed.structured()).isTrue();
        assertThat(parsed.year()).isEqualTo(2020);
        assertThat(parsed.number()).isEqualTo("3(1)");
        assertThat(parsed.yearNumberLiteral()).isEqualTo("2020-3(1)");
    }

    @Test
    void treatsPlainKeywordsAsBroadSearch() {
        for (String query : List.of("极限", "数学一", "7", "A-3", "2020-")) {
            QuestionSearchQuery parsed = QuestionSearchQuery.parse(query);
            assertThat(parsed.structured()).as(query).isFalse();
            assertThat(parsed.year()).as(query).isNull();
            assertThat(parsed.number()).as(query).isNull();
            assertThat(parsed.keyword()).as(query).isEqualTo(query.trim());
            assertThat(parsed.likePattern()).as(query).isEqualTo("%" + query.trim().toLowerCase() + "%");
        }
    }

    @Test
    void blankQueryHasNoKeyword() {
        assertThat(QuestionSearchQuery.parse(null).keyword()).isNull();
        assertThat(QuestionSearchQuery.parse("   ").keyword()).isNull();
        assertThat(QuestionSearchQuery.parse("").structured()).isFalse();
    }
}

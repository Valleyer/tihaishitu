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

    /**
     * 排序键必须把“年份前缀剥离”表达成“只在题号带相同 exam_year 前缀时才剥离”。
     *
     * <p>旧实现取“连接符前片段”，会把 exam_year=2020 + {@code "2020-7"} 当成 2020。
     * 这里锁定生成的 SQL 语义，防止再次退回那种写法。</p>
     */
    @Test
    void naturalKeyStripsYearPrefixOnlyForTheSameExamYear() {
        String expression = QuestionNumberSort.naturalKey("q.question_number", "q.exam_year");
        // 只有“题号 = '<exam_year>-' + 后续内容”才取连接符之后的部分。
        assertThat(expression).contains("CHAR_LENGTH(CONCAT(q.exam_year,'-'))");
        assertThat(expression).contains("CONCAT(q.exam_year,'-') = LEFT(");
        assertThat(expression).contains("SUBSTRING(");
        // 非纯数字题号必须落入“最后一档”，不能取到连接符之后的内容。
        assertThat(expression).contains("THEN ''");
        assertThat(expression).contains("LPAD(");
        // 不使用 MySQL 5.7 不支持的函数与 Window Function。
        assertThat(expression).doesNotContain("REGEXP");
        assertThat(expression).doesNotContain("SUBSTRING_INDEX");
        assertThat(expression).doesNotContain("ROW_NUMBER");
        assertThat(expression).doesNotContain("OVER(");
    }

    @Test
    void orderByComposesSourceYearNaturalNumberAndId() {
        String order = QuestionNumberSort.orderBy("COALESCE(s.display_name,'')", "q.exam_year",
                "q.question_number", "q.id");
        assertThat(order).contains("COALESCE(s.display_name,'')");
        assertThat(order).contains("LPAD(COALESCE(q.exam_year,9999), 4, '0')");
        assertThat(order).contains(QuestionNumberSort.naturalKey("q.question_number", "q.exam_year"));
        assertThat(order).contains("COALESCE(q.id,'')");
    }
}

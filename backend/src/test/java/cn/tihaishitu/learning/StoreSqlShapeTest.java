package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Store 分页 SQL 形状的守卫。
 *
 * <p>真实 MySQL 5.7 上“{@code SELECT DISTINCT} + {@code ORDER BY} 引用未出现在 SELECT list
 * 的表达式”在严格 sql_mode 下有报错风险，而全平台题库的 outer query 只有一对一 JOIN
 * （Book / Chapter / Knowledge 都走 EXISTS），本来也不需要去重。H2 的 MySQL 兼容模式
 * 不一定复现这个差异，所以这里直接断言两个 Store 生成的分页 SQL 形状，
 * 防止以后有人重新加回 {@code DISTINCT}。</p>
 */
class StoreSqlShapeTest {
    private static final Path LEARNING_STORE = Path.of("src/main/java/cn/tihaishitu/learning/LearningBrowseStore.java");
    private static final Path MANAGEMENT_STORE = Path.of("src/main/java/cn/tihaishitu/manage/QuestionManagementStore.java");

    @Test
    void learningQuestionPagingSelectsPlainIdWhileTotalStaysDistinct() throws IOException {
        String source = read(LEARNING_STORE);
        assertThat(source).contains("SELECT q.id FROM question_resource q ");
        // 去重只发生在总数上，分页 ID 查询不再依赖 MySQL 对 DISTINCT + 非 select ORDER BY 的宽松行为。
        assertThat(source).contains("SELECT COUNT(DISTINCT q.id) FROM question_resource q ");
        assertThat(source).doesNotContain("SELECT DISTINCT q.id FROM question_resource q");
    }

    @Test
    void managementQuestionPagingSelectsPlainRowsAndCountsStars() throws IOException {
        String source = read(MANAGEMENT_STORE);
        assertThat(source).contains("SELECT COUNT(*) FROM question_resource q ");
        assertThat(source).contains("ORDER BY \" + orderBy() + \" LIMIT ? OFFSET ?");
        assertThat(source).doesNotContain("SELECT DISTINCT q.*");
    }

    @Test
    void naturalKeyIsBuiltFromBothQuestionNumberAndExamYear() throws IOException {
        String source = read(LEARNING_STORE);
        assertThat(source).contains("QuestionNumberSort.orderBy(");
        // 排序键必须同时拿到题号与年份，才能只在“同年份前缀”时剥离年份。
        String expression = QuestionNumberSort.naturalKey("q.question_number", "q.exam_year");
        assertThat(expression).contains("CONCAT(q.exam_year,'-')");
        assertThat(source).doesNotContain("QuestionNumberSort.naturalKey(\"q.question_number\")");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

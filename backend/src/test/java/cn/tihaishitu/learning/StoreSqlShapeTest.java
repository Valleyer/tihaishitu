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
    private static final Path PRACTICE_SELECTION_STORE =
            Path.of("src/main/java/cn/tihaishitu/learning/PracticeSelectionStore.java");

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

    /**
     * H2 的 MySQL 模式接受 {@code cursor} 表别名，真实 MySQL 5.7/8 会把它按关键字语义解析。
     * 集成测试覆盖查询行为；这条源码形状守卫专门锁住两者之间无法由 H2 暴露的兼容性差异。
     */
    @Test
    void randomAttemptCursorUsesMysqlSafeAlias() throws IOException {
        String source = read(PRACTICE_SELECTION_STORE);
        assertThat(source).contains("FROM learner_random_attempt_cursor rac");
        assertThat(source).contains("attempt.id = rac.last_random_attempt_id");
        assertThat(source).doesNotContain("learner_random_attempt_cursor cursor");
    }

    /**
     * PR7 进度统计 V3 的单一事实来源守卫。
     *
     * <p>「有效 Attempt」投影只能有一份 SQL。历史问题是 Progress 与 Statistics 各自聚合一次，
     * 导致同一指标出现两个答案；这里直接断言 reveal 过滤条件只存在于
     * {@link LearnerActivityStore}，防止以后有人再往 Service 里塞第二套 SQL。</p>
     */
    @Test
    void effectiveAttemptProjectionLivesOnlyInTheActivityStore() throws IOException {
        Path learningDir = Path.of("src/main/java/cn/tihaishitu/learning");
        try (var files = Files.list(learningDir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (file.getFileName().toString().equals("LearnerActivityStore.java")) {
                    assertThat(source).contains("answer_revealed_at");
                    continue;
                }
                // 其他学习类不得自带「有效 Attempt」的 reveal / 状态过滤投影。
                assertThat(source)
                        .as("%s 不应再写一套有效 Attempt SQL", file.getFileName())
                        .doesNotContain("answer_revealed_at");
            }
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}

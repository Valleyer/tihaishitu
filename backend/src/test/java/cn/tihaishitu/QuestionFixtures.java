package cn.tihaishitu;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * 测试夹具共用工具。
 *
 * <p>Question Contract V2 之后，正式题的唯一答案事实是
 * {@code question_resource_option.correct_option}；只写
 * {@code question_resource.standard_answer_json} 的旧夹具会让正式发题
 * 在派生运行时答案时失败，因此所有造题夹具都必须同时写入选项事实。
 */
final class QuestionFixtures {
    private QuestionFixtures() {}

    /** 为判断题写入 true / false 两个选项，其中 true 为正确项。 */
    static void trueFalseOptions(JdbcTemplate jdbc, String questionId) {
        insert(jdbc, questionId, "true", "正确", true, 0);
        insert(jdbc, questionId, "false", "错误", false, 1);
    }

    private static void insert(JdbcTemplate jdbc, String questionId, String key, String text,
                               boolean correct, int sortOrder) {
        jdbc.update("""
                INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), questionId, key, text, correct, sortOrder);
    }
}

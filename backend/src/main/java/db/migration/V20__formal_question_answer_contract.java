package db.migration;

import cn.tihaishitu.catalog.SolutionAnalysisComposer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Formal Question Contract V2：题库答案退场，Attempt 判题快照继续保留。 */
public class V20__formal_question_answer_contract extends BaseJavaMigration {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private record Option(String key, boolean correct) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        validatePublishedObjectiveQuestions(connection);
        mergeLegacySolutionContent(connection);
        makeLegacyColumnNullable(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE question_resource SET standard_answer_json=NULL WHERE parent_question_id IS NULL")) {
            statement.executeUpdate();
        }
    }

    private static void validatePublishedObjectiveQuestions(Connection connection) throws Exception {
        try (PreparedStatement questions = connection.prepareStatement("""
                SELECT id,question_type FROM question_resource
                 WHERE parent_question_id IS NULL AND status='published'
                   AND question_type IN ('single_choice','multiple_choice','true_false')
                """); ResultSet rows = questions.executeQuery();
             PreparedStatement options = connection.prepareStatement("""
                SELECT option_key,correct_option FROM question_resource_option
                 WHERE question_id=? ORDER BY sort_order,option_key
                """)) {
            while (rows.next()) {
                String id = rows.getString(1);
                String type = rows.getString(2);
                options.setString(1, id);
                List<Option> values = new ArrayList<>();
                try (ResultSet optionRows = options.executeQuery()) {
                    while (optionRows.next()) values.add(new Option(optionRows.getString(1), optionRows.getBoolean(2)));
                }
                long correct = values.stream().filter(Option::correct).count();
                boolean valid = switch (type) {
                    case "single_choice" -> values.size() >= 2 && correct == 1;
                    case "multiple_choice" -> values.size() >= 2 && correct >= 2;
                    case "true_false" -> values.size() == 2 && correct == 1
                            && keys(values).equals(Set.of("true", "false"));
                    default -> true;
                };
                if (!valid) throw new IllegalStateException(
                        "Published Formal Question 选项答案契约非法，Question ID=" + id + "，type=" + type);
            }
        }
    }

    private static Set<String> keys(List<Option> options) {
        Set<String> result = new HashSet<>();
        options.forEach(option -> result.add(option.key()));
        return result;
    }

    private static void mergeLegacySolutionContent(Connection connection) throws Exception {
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT id,standard_answer_json,analysis_markdown FROM question_resource
                 WHERE parent_question_id IS NULL AND question_type='solution'
                """); ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE question_resource SET analysis_markdown=? WHERE id=?")) {
            while (rows.next()) {
                String answer = legacyText(rows.getString(2));
                String analysis = rows.getString(3);
                update.setString(1, SolutionAnalysisComposer.merge(answer, analysis));
                update.setString(2, rows.getString(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private static String legacyText(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            JsonNode value = MAPPER.readTree(json);
            if (value == null || value.isNull()) return "";
            return value.isTextual() ? value.asText() : value.toString();
        } catch (Exception ignored) {
            // 历史脏数据也不能静默丢失；无法解析时按原文并入解析。
            return json.strip();
        }
    }

    private static void makeLegacyColumnNullable(Connection connection) throws Exception {
        String database = connection.getMetaData().getDatabaseProductName();
        String sql = database != null && database.toLowerCase().contains("h2")
                ? "ALTER TABLE question_resource ALTER COLUMN standard_answer_json DROP NOT NULL"
                : "ALTER TABLE question_resource MODIFY COLUMN standard_answer_json LONGTEXT NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }
}

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Formal Question Contract V2：题库答案退场，Attempt 判题快照继续保留。
 *
 * <p>迁移只做确定性的结构收口，不猜任何内容：
 * <ol>
 *   <li>先校验 published Formal 题目仍满足正式契约（与 {@code QuestionContractValidator} 对齐）；</li>
 *   <li>把旧综合题 standard answer 并进 analysis_markdown；</li>
 *   <li>再次校验 published Formal 综合题合并后的 analysis 非空；</li>
 *   <li>把 standard_answer_json 改为可空；</li>
 *   <li>清空 Formal Parent 的 standard_answer_json，Remedial 子题保持原样。</li>
 * </ol>
 */
public class V20__formal_question_answer_contract extends BaseJavaMigration {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private record Option(String key, String text, boolean correct) {}

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        validatePublishedFormalContract(connection);
        mergeLegacySolutionContent(connection);
        validatePublishedSolutionAnalysis(connection);
        makeLegacyColumnNullable(connection);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE question_resource SET standard_answer_json=NULL WHERE parent_question_id IS NULL")) {
            statement.executeUpdate();
        }
    }

    /**
     * 与 {@code QuestionContractValidator.validateFormal} 保持同一套合法性标准，
     * 避免 migration 放行、应用启动后却认为题目非法。
     */
    private static void validatePublishedFormalContract(Connection connection) throws Exception {
        try (PreparedStatement questions = connection.prepareStatement("""
                SELECT id,question_type,presentation_type,grading_mode FROM question_resource
                 WHERE parent_question_id IS NULL AND status='published'
                   AND question_type IN ('single_choice','multiple_choice','true_false','solution')
                """); ResultSet rows = questions.executeQuery();
             PreparedStatement options = connection.prepareStatement("""
                SELECT option_key,option_text,correct_option FROM question_resource_option
                 WHERE question_id=? ORDER BY sort_order,option_key
                """)) {
            while (rows.next()) {
                String id = rows.getString(1);
                String type = rows.getString(2);
                String presentation = rows.getString(3);
                String grading = rows.getString(4);
                require(expectedPresentation(type).equals(presentation) && expectedGrading(type).equals(grading),
                        "Published Formal Question 题型、展示类型与判题模式不匹配，Question ID=" + id
                                + "，type=" + type + "，presentation=" + presentation + "，grading=" + grading);
                options.setString(1, id);
                List<Option> values = new ArrayList<>();
                try (ResultSet optionRows = options.executeQuery()) {
                    while (optionRows.next()) {
                        values.add(new Option(optionRows.getString(1), optionRows.getString(2),
                                optionRows.getBoolean(3)));
                    }
                }
                if ("solution".equals(type)) {
                    require(values.isEmpty(),
                            "Published Formal 综合题不能提供客观题选项，Question ID=" + id);
                    continue;
                }
                require(values.size() >= 2 && values.size() <= 6,
                        "Published Formal 客观题必须提供 2–6 个选项，Question ID=" + id
                                + "，选项数=" + values.size());
                Set<String> keys = new LinkedHashSet<>();
                long correct = 0;
                for (Option option : values) {
                    String key = option.key() == null ? "" : option.key().trim();
                    require(!key.isEmpty() && option.text() != null && !option.text().isBlank(),
                            "Published Formal 客观题选项键和值不能为空，Question ID=" + id);
                    require(keys.add(key),
                            "Published Formal 客观题选项键不能重复，Question ID=" + id + "，key=" + key);
                    if (option.correct()) correct++;
                }
                boolean valid = switch (type) {
                    case "single_choice" -> correct == 1;
                    case "multiple_choice" -> correct >= 2;
                    case "true_false" -> values.size() == 2 && correct == 1
                            && keys.equals(Set.of("true", "false"));
                    default -> false;
                };
                require(valid, "Published Formal Question 选项答案契约非法，Question ID=" + id + "，type=" + type);
            }
        }
    }

    /**
     * Formal 综合题的唯一答案事实是 analysis_markdown。合并旧 standard answer 之后，
     * published Formal 综合题的 analysis 必须非空，否则直接 fail 并指出 Question ID。
     */
    private static void validatePublishedSolutionAnalysis(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM question_resource
                 WHERE parent_question_id IS NULL AND status='published' AND question_type='solution'
                   AND (analysis_markdown IS NULL OR TRIM(analysis_markdown)='')
                """); ResultSet rows = statement.executeQuery()) {
            List<String> empty = new ArrayList<>();
            while (rows.next()) empty.add(rows.getString(1));
            require(empty.isEmpty(), "Published Formal 综合题合并后 analysis_markdown 为空，Question ID="
                    + String.join("、", empty));
        }
    }

    private static String expectedPresentation(String type) {
        return "solution".equals(type) ? "self_assessment" : type;
    }

    private static String expectedGrading(String type) {
        return "solution".equals(type) ? "self_assessment" : "auto";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
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

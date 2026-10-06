package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.learning.KnowledgeQuestionCoveragePolicy;
import cn.tihaishitu.learning.TrainableKnowledge;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class KnowledgeQuestionPoolStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeQuestionPoolStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<KnowledgePointDto> bookScope(Set<String> requestedBookIds) {
        List<String> bookIds = enabledBookIds(requestedBookIds);
        if (bookIds.isEmpty()) return List.of();

        Set<String> modernBooks = new LinkedHashSet<>(jdbc.query(
                "SELECT DISTINCT bank_id FROM question_bank_knowledge WHERE bank_id IN ("
                        + placeholders(bookIds.size()) + ")",
                (result, row) -> result.getString("bank_id"), bookIds.toArray()));
        List<String> legacyBooks = bookIds.stream().filter(id -> !modernBooks.contains(id)).toList();
        Map<String, KnowledgePointDto> points = new LinkedHashMap<>();
        if (!modernBooks.isEmpty()) {
            loadPoints("""
                    SELECT DISTINCT k.id, k.name, k.subject_name, k.section_name, k.chapter_name,
                           k.description, k.explanation, k.sort_order
                      FROM question_bank_knowledge bk
                     JOIN global_knowledge_point k ON k.id = bk.knowledge_point_id
                     WHERE bk.bank_id IN (%s) AND k.status = 'active'
                       AND %s
                     ORDER BY k.sort_order, k.id
                    """.formatted(placeholders(modernBooks.size()), TrainableKnowledge.exists("k")), modernBooks, points);
        }
        if (!legacyBooks.isEmpty()) {
            loadPoints("""
                    SELECT DISTINCT k.id, k.name, k.subject_name, k.section_name, k.chapter_name,
                           k.description, k.explanation, k.sort_order
                      FROM legacy_knowledge_map legacy
                     JOIN global_knowledge_point k ON k.id = legacy.global_id
                     WHERE legacy.bank_id IN (%s) AND k.status = 'active'
                       AND %s
                     ORDER BY k.sort_order, k.id
                    """.formatted(placeholders(legacyBooks.size()), TrainableKnowledge.exists("k")), legacyBooks, points);
        }
        return List.copyOf(points.values());
    }

    /**
     * 有正式题可练（至少一道 published 正式父题与该知识点有关系）的知识点。
     * core / auxiliary 都算覆盖，不再要求题目的其他关联知识点处于 allowed scope 或 ready。
     */
    public Set<String> playableKnowledgePointIds(Set<String> allowedKnowledgePointIds) {
        if (allowedKnowledgePointIds.isEmpty()) return Set.of();
        String marks = placeholders(allowedKnowledgePointIds.size());
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT current_rel.knowledge_point_id
                  FROM question_resource q
                  JOIN question_resource_knowledge current_rel ON current_rel.question_id = q.id
                  JOIN global_knowledge_point current_k ON current_k.id = current_rel.knowledge_point_id
                 WHERE q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                   AND current_rel.knowledge_point_id IN (%s)
                   AND %s
                   AND current_k.status = 'active'
                """.formatted(marks, KnowledgeQuestionCoveragePolicy.anyRelationRole("current_rel")),
                (result, row) -> result.getString("knowledge_point_id"), allowedKnowledgePointIds.toArray()));
    }

    /**
     * 某个 KnowledgePoint 的正式题候选：只要题目与该知识点有关系（core 或 auxiliary）即属于该专项候选。
     * 不再使用 dependency readiness、Mastery、Review due 或 preferred difficulty 过滤候选。
     */
    public List<QuestionDto> candidatesForKnowledge(String currentKnowledgePointId,
                                                    Set<String> allowedKnowledgePointIds) {
        Set<String> allowed = new LinkedHashSet<>(allowedKnowledgePointIds);
        allowed.add(currentKnowledgePointId);
        String marks = placeholders(allowed.size());
        List<Object> args = new ArrayList<>();
        // 绑定顺序必须与 SQL 文本中 '?' 的出现顺序一致：当前知识点在前，范围去重集合在后。
        args.add(currentKnowledgePointId);
        args.addAll(allowed);
        List<QuestionRow> rows = jdbc.query("""
                SELECT q.id, q.subject_name, q.source_type, q.source_name, q.exam_year, q.question_number,
                       q.question_type, q.presentation_type, q.grading_mode, q.content_markdown,
                       q.standard_answer_json, q.analysis_markdown, q.difficulty
                  FROM question_resource q
                  JOIN question_resource_knowledge current_rel ON current_rel.question_id = q.id
                  JOIN global_knowledge_point current_k ON current_k.id = current_rel.knowledge_point_id
                 WHERE q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                   AND current_rel.knowledge_point_id = ?
                   AND current_rel.knowledge_point_id IN (%s)
                   AND %s
                   AND current_k.status = 'active'
                 ORDER BY q.id
                """.formatted(marks, KnowledgeQuestionCoveragePolicy.anyRelationRole("current_rel")),
                (result, row) -> questionRow(result), args.toArray());
        return questions(rows);
    }

    /** 题目全部关联知识点及其 core / auxiliary 角色，题面标签与 `question_snapshot_json` 共用。 */
    public Map<String, List<QuestionKnowledge>> questionKnowledge(Collection<String> questionIds) {
        if (questionIds.isEmpty()) return Map.of();
        Map<String, List<QuestionKnowledge>> relations = new LinkedHashMap<>();
        jdbc.query("""
                SELECT qk.question_id, k.id knowledge_point_id, k.name, qk.relation_role
                  FROM question_resource_knowledge qk
                  JOIN global_knowledge_point k ON k.id = qk.knowledge_point_id
                 WHERE qk.question_id IN (%s) AND k.status = 'active'
                 ORDER BY qk.question_id, qk.sort_order, k.id
                """.formatted(placeholders(questionIds.size())), (RowCallbackHandler) result -> relations
                .computeIfAbsent(result.getString("question_id"), ignored -> new ArrayList<>())
                .add(new QuestionKnowledge(result.getString("knowledge_point_id"), result.getString("name"),
                        result.getString("relation_role"))), questionIds.toArray());
        return relations;
    }

    public List<QuestionKnowledge> questionKnowledge(String questionId) {
        return questionKnowledge(List.of(questionId)).getOrDefault(questionId, List.of());
    }

    /** 题目的来源元数据；真题展示标签由 exam_year + subject_name 动态生成，不新增 tag 表。 */
    public record QuestionSource(String subjectName, String sourceName, Integer examYear, String questionNumber) {}

    public Optional<QuestionSource> questionSource(String questionId) {
        return jdbc.query("""
                SELECT subject_name, source_name, exam_year, question_number
                  FROM question_resource WHERE id = ?
                """, (result, row) -> new QuestionSource(result.getString("subject_name"),
                result.getString("source_name"), result.getObject("exam_year", Integer.class),
                result.getString("question_number")), questionId).stream().findFirst();
    }

    public record QuestionKnowledge(String knowledgePointId, String name, String role) {}

    public List<KnowledgePointDto> knowledgeDetails(Collection<String> knowledgePointIds) {
        if (knowledgePointIds.isEmpty()) return List.of();
        Map<String, KnowledgePointDto> points = new LinkedHashMap<>();
        loadPoints("""
                SELECT k.id, k.name, k.subject_name, k.section_name, k.chapter_name,
                       k.description, k.explanation, k.sort_order
                  FROM global_knowledge_point k
                 WHERE k.id IN (%s) AND k.status = 'active'
                 ORDER BY k.sort_order, k.id
                """.formatted(placeholders(knowledgePointIds.size())), knowledgePointIds, points);
        return List.copyOf(points.values());
    }

    private List<String> enabledBookIds(Set<String> requestedBookIds) {
        if (requestedBookIds.isEmpty()) {
            return jdbc.query("SELECT id FROM question_bank WHERE enabled = TRUE ORDER BY created_at, id",
                    (result, row) -> result.getString("id"));
        }
        return jdbc.query("SELECT id FROM question_bank WHERE enabled = TRUE AND id IN ("
                        + placeholders(requestedBookIds.size()) + ") ORDER BY created_at, id",
                (result, row) -> result.getString("id"), requestedBookIds.toArray());
    }

    private void loadPoints(String sql, Collection<String> ids,
                            Map<String, KnowledgePointDto> destination) {
        jdbc.query(sql, (RowCallbackHandler) result -> {
            KnowledgePointDto point = knowledgePoint(result);
            destination.putIfAbsent(point.id(), point);
        }, ids.toArray());
    }

    private Map<String, Map<String, String>> loadOptions(List<String> questionIds) {
        Map<String, Map<String, String>> options = new LinkedHashMap<>();
        jdbc.query("""
                SELECT question_id, option_key, option_text
                  FROM question_resource_option
                 WHERE question_id IN (%s)
                 ORDER BY question_id, sort_order, option_key
                """.formatted(placeholders(questionIds.size())), (RowCallbackHandler) result -> options
                .computeIfAbsent(result.getString("question_id"), ignored -> new LinkedHashMap<>())
                .put(result.getString("option_key"), result.getString("option_text")), questionIds.toArray());
        return options;
    }

    private List<QuestionDto> questions(List<QuestionRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> questionIds = rows.stream().map(QuestionRow::id).toList();
        Map<String, Map<String, String>> options = loadOptions(questionIds);
        Map<String, List<QuestionKnowledge>> knowledge = questionKnowledge(questionIds);
        return rows.stream().map(row -> new QuestionDto(
                row.id(), row.subject(), row.sourceType(), value(row.sourceName(), "全服题库"),
                row.presentationType(), row.questionType(), row.presentationType(), row.gradingMode(),
                row.content(), options.getOrDefault(row.id(), Map.of()), readTree(row.answer()), row.analysis(),
                List.of(), List.of(), row.difficulty(), 3, List.of(),
                knowledge.getOrDefault(row.id(), List.of()).stream().map(QuestionKnowledge::knowledgePointId).toList(),
                true
        )).toList();
    }

    private static KnowledgePointDto knowledgePoint(ResultSet result) throws SQLException {
        return new KnowledgePointDto(
                result.getString("id"), result.getString("name"), result.getString("subject_name"),
                result.getString("section_name"), result.getString("description"),
                result.getString("explanation"), null, List.of(),
                List.of(result.getString("chapter_name")));
    }

    private static QuestionRow questionRow(ResultSet result) throws SQLException {
        return new QuestionRow(
                result.getString("id"), result.getString("subject_name"), result.getString("source_type"),
                result.getString("source_name"), result.getObject("exam_year", Integer.class),
                result.getString("question_number"), result.getString("question_type"),
                result.getString("presentation_type"), result.getString("grading_mode"),
                result.getString("content_markdown"), result.getString("standard_answer_json"),
                result.getString("analysis_markdown"), result.getInt("difficulty"));
    }

    private JsonNode readTree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库中的答案不是合法 JSON。", error);
        }
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record QuestionRow(
            String id, String subject, String sourceType, String sourceName, Integer examYear,
            String questionNumber, String questionType,
            String presentationType, String gradingMode, String content, String answer,
            String analysis, int difficulty) {}
}

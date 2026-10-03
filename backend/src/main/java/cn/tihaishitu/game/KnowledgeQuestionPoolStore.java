package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
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
                     ORDER BY k.sort_order, k.id
                    """.formatted(placeholders(modernBooks.size())), modernBooks, points);
        }
        if (!legacyBooks.isEmpty()) {
            loadPoints("""
                    SELECT DISTINCT k.id, k.name, k.subject_name, k.section_name, k.chapter_name,
                           k.description, k.explanation, k.sort_order
                      FROM legacy_knowledge_map legacy
                      JOIN global_knowledge_point k ON k.id = legacy.global_id
                     WHERE legacy.bank_id IN (%s) AND k.status = 'active'
                     ORDER BY k.sort_order, k.id
                    """.formatted(placeholders(legacyBooks.size())), legacyBooks, points);
        }
        return List.copyOf(points.values());
    }

    public Set<String> playableKnowledgePointIds(Set<String> allowedKnowledgePointIds) {
        if (allowedKnowledgePointIds.isEmpty()) return Set.of();
        String marks = placeholders(allowedKnowledgePointIds.size());
        List<Object> args = new ArrayList<>();
        args.addAll(allowedKnowledgePointIds);
        args.addAll(allowedKnowledgePointIds);
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT current_rel.knowledge_point_id
                  FROM question_resource q
                  JOIN question_resource_knowledge current_rel ON current_rel.question_id = q.id
                  JOIN global_knowledge_point current_k ON current_k.id = current_rel.knowledge_point_id
                 WHERE q.status = 'published'
                   AND current_rel.relation_role = 'core'
                   AND current_rel.knowledge_point_id IN (%s)
                   AND current_k.status = 'active'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM question_resource_knowledge dependency
                         LEFT JOIN global_knowledge_point dependency_k
                           ON dependency_k.id = dependency.knowledge_point_id
                        WHERE dependency.question_id = q.id
                          AND (dependency_k.id IS NULL OR dependency_k.status <> 'active'
                               OR dependency.knowledge_point_id NOT IN (%s))
                   )
                """.formatted(marks, marks),
                (result, row) -> result.getString("knowledge_point_id"), args.toArray()));
    }

    public List<QuestionDto> candidatesForCore(
            String currentKnowledgePointId, Set<String> allowedKnowledgePointIds) {
        Set<String> allowed = new LinkedHashSet<>(allowedKnowledgePointIds);
        allowed.add(currentKnowledgePointId);
        String marks = placeholders(allowed.size());
        List<Object> args = new ArrayList<>();
        args.add(currentKnowledgePointId);
        args.addAll(allowed);
        List<QuestionRow> rows = jdbc.query("""
                SELECT q.id, q.subject_name, q.source_type, q.source_name, q.question_type,
                       q.presentation_type, q.grading_mode, q.content_markdown,
                       q.standard_answer_json, q.analysis_markdown, q.difficulty
                  FROM question_resource q
                  JOIN question_resource_knowledge current_rel ON current_rel.question_id = q.id
                  JOIN global_knowledge_point current_k ON current_k.id = current_rel.knowledge_point_id
                 WHERE q.status = 'published'
                   AND current_rel.knowledge_point_id = ?
                   AND current_rel.relation_role = 'core'
                   AND current_k.status = 'active'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM question_resource_knowledge dependency
                         LEFT JOIN global_knowledge_point dependency_k
                           ON dependency_k.id = dependency.knowledge_point_id
                        WHERE dependency.question_id = q.id
                          AND (dependency_k.id IS NULL OR dependency_k.status <> 'active'
                               OR dependency.knowledge_point_id NOT IN (%s))
                   )
                 ORDER BY q.id
                """.formatted(marks), (result, row) -> questionRow(result), args.toArray());
        if (rows.isEmpty()) return List.of();

        List<String> questionIds = rows.stream().map(QuestionRow::id).toList();
        Map<String, Map<String, String>> options = loadOptions(questionIds);
        Map<String, List<String>> knowledge = loadQuestionKnowledge(questionIds);
        return rows.stream().map(row -> new QuestionDto(
                row.id(), row.subject(), row.sourceType(), value(row.sourceName(), "全服题库"),
                row.presentationType(), row.questionType(), row.presentationType(), row.gradingMode(),
                row.content(), options.getOrDefault(row.id(), Map.of()), readTree(row.answer()), row.analysis(),
                List.of(), List.of(), row.difficulty(), 3, List.of(),
                knowledge.getOrDefault(row.id(), List.of()), true
        )).toList();
    }

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

    private Map<String, List<String>> loadQuestionKnowledge(List<String> questionIds) {
        Map<String, List<String>> relations = new LinkedHashMap<>();
        jdbc.query("""
                SELECT question_id, knowledge_point_id
                  FROM question_resource_knowledge
                 WHERE question_id IN (%s)
                 ORDER BY question_id, sort_order, knowledge_point_id
                """.formatted(placeholders(questionIds.size())), (RowCallbackHandler) result -> relations
                .computeIfAbsent(result.getString("question_id"), ignored -> new ArrayList<>())
                .add(result.getString("knowledge_point_id")), questionIds.toArray());
        return relations;
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
                result.getString("source_name"), result.getString("question_type"),
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
            String id, String subject, String sourceType, String sourceName, String questionType,
            String presentationType, String gradingMode, String content, String answer,
            String analysis, int difficulty) {}
}

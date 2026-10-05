package cn.tihaishitu.learning;

import cn.tihaishitu.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class LearningBrowseStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public LearningBrowseStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public List<Map<String, Object>> books() {
        return jdbc.query("""
                SELECT b.id, b.name, b.description, b.revision,
                       COUNT(DISTINCT bk.knowledge_point_id) knowledge_count,
                       COUNT(DISTINCT CASE WHEN q.status = 'published' THEN q.id END) question_count
                  FROM question_bank b
                  LEFT JOIN question_bank_knowledge bk ON bk.bank_id = b.id
                  LEFT JOIN question_resource_knowledge qk ON qk.knowledge_point_id = bk.knowledge_point_id
                  LEFT JOIN question_resource q ON q.id = qk.question_id
                 WHERE b.enabled = TRUE
                 GROUP BY b.id, b.name, b.description, b.revision, b.created_at
                 ORDER BY b.created_at, b.id
                """, (result, row) -> ordered(
                "id", result.getString("id"), "name", result.getString("name"),
                "description", result.getString("description"), "revision", result.getLong("revision"),
                "knowledgePointCount", result.getInt("knowledge_count"),
                "questionCount", result.getInt("question_count")));
    }

    public Map<String, Object> book(String id) {
        Map<String, Object> book = jdbc.query("SELECT id, name, description, revision FROM question_bank WHERE id = ? AND enabled = TRUE",
                (result, row) -> ordered("id", result.getString("id"), "name", result.getString("name"),
                        "description", result.getString("description"), "revision", result.getLong("revision")), id)
                .stream().findFirst().orElseThrow(() -> missing("文集不存在或已停用。"));
        List<Map<String, Object>> chapters = jdbc.query("""
                SELECT id, parent_id, chapter_code, name, description, sort_order
                  FROM question_bank_chapter WHERE bank_id = ? ORDER BY sort_order, id
                """, (result, row) -> ordered("id", result.getString("id"), "parentId", result.getString("parent_id"),
                "code", result.getString("chapter_code"), "name", result.getString("name"),
                "description", result.getString("description"), "sortOrder", result.getInt("sort_order")), id);
        Map<String, List<Map<String, Object>>> points = new LinkedHashMap<>();
        jdbc.query("""
                SELECT bk.chapter_id, k.id, k.code, k.name, k.subject_name, k.section_name,
                       k.chapter_name, k.description, k.explanation, bk.sort_order
                  FROM question_bank_knowledge bk JOIN global_knowledge_point k ON k.id = bk.knowledge_point_id
                 WHERE bk.bank_id = ? AND k.status = 'active' ORDER BY bk.sort_order, k.id
                """, (RowCallbackHandler) result -> points.computeIfAbsent(result.getString("chapter_id"), ignored -> new ArrayList<>())
                .add(knowledge(result)), id);
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        chapters.forEach(chapter -> {
            Map<String, Object> node = new LinkedHashMap<>(chapter);
            node.put("knowledgePoints", points.getOrDefault(chapter.get("id"), List.of()));
            node.put("children", new ArrayList<Map<String, Object>>());
            nodes.put((String) chapter.get("id"), node);
        });
        List<Map<String, Object>> tree = new ArrayList<>();
        nodes.values().forEach(node -> {
            String parentId = (String) node.get("parentId");
            if (parentId == null || !nodes.containsKey(parentId)) tree.add(node);
            else ((List<Map<String, Object>>) nodes.get(parentId).get("children")).add(node);
        });
        Map<String, Object> result = new LinkedHashMap<>(book);
        result.put("chapters", tree);
        return result;
    }

    public Map<String, Object> knowledge(String id) {
        return jdbc.query("""
                SELECT id, code, name, subject_name, section_name, chapter_name, description, explanation, revision
                  FROM global_knowledge_point WHERE id = ? AND status = 'active'
                """, (result, row) -> {
            Map<String, Object> value = knowledge(result);
            value.put("revision", result.getLong("revision"));
            value.put("books", jdbc.query("""
                    SELECT b.id, b.name FROM question_bank_knowledge bk
                    JOIN question_bank b ON b.id = bk.bank_id
                    WHERE bk.knowledge_point_id = ? AND b.enabled = TRUE ORDER BY b.name
                    """, (books, index) -> ordered("id", books.getString("id"), "name", books.getString("name")), id));
            return value;
        }, id).stream().findFirst().orElseThrow(() -> missing("知识点不存在或已停用。"));
    }

    public List<Map<String, Object>> knowledgePoints(String query, String bookId, String chapterId, String subject) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        clauses.add("k.status = 'active'");
        if (query != null && !query.isBlank()) {
            clauses.add("(LOWER(k.name) LIKE ? OR LOWER(k.code) LIKE ? OR EXISTS "
                    + "(SELECT 1 FROM knowledge_alias a WHERE a.knowledge_point_id=k.id AND LOWER(a.alias) LIKE ?))");
            String value = "%" + query.trim().toLowerCase() + "%";
            params.add(value); params.add(value); params.add(value);
        }
        if (bookId != null && !bookId.isBlank()) { clauses.add("bk.bank_id = ?"); params.add(bookId); }
        if (chapterId != null && !chapterId.isBlank()) { clauses.add("bk.chapter_id = ?"); params.add(chapterId); }
        if (subject != null && !subject.isBlank()) { clauses.add("k.subject_name = ?"); params.add(subject); }
        return jdbc.query("""
                SELECT k.id,k.code,k.name,k.subject_name,k.section_name,k.chapter_name,
                       MIN(b.id) book_id,MIN(b.name) book_name,MIN(c.id) chapter_id,MIN(c.name) catalog_chapter,
                       COUNT(DISTINCT CASE WHEN q.status='published' THEN q.id END) published_count
                  FROM global_knowledge_point k
                  JOIN question_bank_knowledge bk ON bk.knowledge_point_id=k.id
                  JOIN question_bank b ON b.id=bk.bank_id AND b.enabled=TRUE
                  JOIN question_bank_chapter c ON c.id=bk.chapter_id
                  LEFT JOIN question_resource_knowledge qk ON qk.knowledge_point_id=k.id
                  LEFT JOIN question_resource q ON q.id=qk.question_id
                 WHERE %s
                 GROUP BY k.id,k.code,k.name,k.subject_name,k.section_name,k.chapter_name,k.sort_order
                 ORDER BY k.sort_order,k.code
                 LIMIT 2000
                """.formatted(String.join(" AND ", clauses)), (row, index) -> ordered(
                "id", row.getString("id"), "code", row.getString("code"), "name", row.getString("name"),
                "subject", row.getString("subject_name"), "section", row.getString("section_name"),
                "chapter", row.getString("chapter_name"), "bookId", row.getString("book_id"),
                "bookName", row.getString("book_name"), "chapterId", row.getString("chapter_id"),
                "catalogChapter", row.getString("catalog_chapter"),
                "publishedQuestionCount", row.getInt("published_count")), params.toArray());
    }

    public List<Map<String, Object>> questionsForKnowledge(String id) {
        List<Map<String, Object>> questions = jdbc.query("""
                SELECT DISTINCT q.id, q.subject_name, q.source_type, q.source_name, q.exam_year,
                       q.question_number, q.question_type,
                       q.presentation_type, q.grading_mode, q.content_markdown, q.analysis_markdown,
                       q.standard_answer_json, q.difficulty, q.revision
                  FROM question_resource q
                  JOIN question_resource_knowledge qk ON qk.question_id = q.id
                 WHERE qk.knowledge_point_id = ? AND q.status = 'published'
                 ORDER BY q.id
                """, (result, row) -> question(result), id);
        questions.forEach(question -> question.put("knowledgePoints", questionKnowledge((String) question.get("id"))));
        return questions;
    }

    public Map<String, Object> question(String id) {
        Map<String, Object> value = jdbc.query("""
                SELECT id, subject_name, source_type, source_name, exam_year, question_number,
                       question_type, presentation_type,
                       grading_mode, content_markdown, analysis_markdown, standard_answer_json, difficulty, revision
                  FROM question_resource WHERE id = ? AND status = 'published'
                """, (result, row) -> question(result), id).stream().findFirst()
                .orElseThrow(() -> missing("题目不存在或尚未发布。"));
        value.put("options", jdbc.query("""
                SELECT option_key, option_text FROM question_resource_option
                 WHERE question_id = ? ORDER BY sort_order, option_key
                """, (result, row) -> ordered("key", result.getString("option_key"), "text", result.getString("option_text")), id));
        value.put("knowledgePoints", questionKnowledge(id));
        return value;
    }

    private List<Map<String, Object>> questionKnowledge(String id) {
        return jdbc.query("""
                SELECT k.id, k.code, k.name, k.subject_name, k.section_name, k.chapter_name,
                       k.description, k.explanation, qk.relation_role, qk.sort_order
                  FROM question_resource_knowledge qk JOIN global_knowledge_point k ON k.id = qk.knowledge_point_id
                 WHERE qk.question_id = ? AND k.status = 'active' ORDER BY qk.sort_order, k.id
                """, (result, row) -> {
            Map<String, Object> point = knowledge(result);
            point.put("role", result.getString("relation_role"));
            return point;
        }, id);
    }

    private Map<String, Object> question(java.sql.ResultSet result) throws java.sql.SQLException {
        return ordered("id", result.getString("id"), "subject", result.getString("subject_name"),
                "sourceType", result.getString("source_type"), "sourceName", result.getString("source_name"),
                "examYear", result.getObject("exam_year", Integer.class),
                "questionNumber", result.getString("question_number"),
                "questionType", result.getString("question_type"), "presentationType", result.getString("presentation_type"),
                "gradingMode", result.getString("grading_mode"), "contentMarkdown", result.getString("content_markdown"),
                "analysisMarkdown", result.getString("analysis_markdown"),
                "standardAnswer", json(result.getString("standard_answer_json")),
                "difficulty", result.getInt("difficulty"), "revision", result.getLong("revision"));
    }

    private static Map<String, Object> knowledge(java.sql.ResultSet result) throws java.sql.SQLException {
        return ordered("id", result.getString("id"), "code", result.getString("code"), "name", result.getString("name"),
                "subject", result.getString("subject_name"), "section", result.getString("section_name"),
                "chapter", result.getString("chapter_name"), "description", result.getString("description"),
                "explanation", result.getString("explanation"));
    }

    private JsonNode json(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("题目答案数据损坏。", error); }
    }

    private static LinkedHashMap<String, Object> ordered(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    private static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }
}

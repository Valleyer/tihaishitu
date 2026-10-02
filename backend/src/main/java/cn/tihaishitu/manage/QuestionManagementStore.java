package cn.tihaishitu.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class QuestionManagementStore {
    public record OptionView(String id, String key, String text, boolean correct, int sortOrder) {}
    public record KnowledgeRelationView(String id, String code, String name, String role, int sortOrder) {}
    public record QuestionView(
            String id, String subject, String sourceType, String sourceName, Integer examYear,
            String questionNumber, String questionType, String presentationType, String gradingMode,
            String content, JsonNode standardAnswer, String analysis, int difficulty, String status,
            String parentQuestionId, String derivationType, String createdBy, String creatorName,
            String reviewedBy, String reviewComment, long revision,
            List<OptionView> options, List<KnowledgeRelationView> knowledgePoints) {}
    public record OptionInput(String key, String text, boolean correct, int sortOrder) {}
    public record RelationInput(String knowledgePointId, String role, int sortOrder) {}
    public record QuestionInput(
            String subject, String sourceType, String sourceName, Integer examYear, String questionNumber,
            String questionType, String presentationType, String gradingMode, String content,
            JsonNode standardAnswer, String analysis, int difficulty, String parentQuestionId,
            String derivationType, List<OptionInput> options, List<RelationInput> knowledgePoints) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeManagementStore knowledgeStore;

    public QuestionManagementStore(JdbcTemplate jdbc, ObjectMapper mapper, KnowledgeManagementStore knowledgeStore) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.knowledgeStore = knowledgeStore;
    }

    public PageResult<QuestionView> search(String query, String subject, String sourceType, Integer examYear,
                                           String questionType, String gradingMode, String status,
                                           String creator, String knowledge, int page, int size) {
        SqlFilter filter = filter(query, subject, sourceType, examYear, questionType, gradingMode,
                status, creator, knowledge);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM question_resource q " + filter.where(),
                Long.class, filter.params().toArray());
        List<Object> params = new ArrayList<>(filter.params());
        params.add(size); params.add(page * size);
        List<QuestionView> rows = jdbc.query(baseSelect() + filter.where()
                        + " ORDER BY q.updated_at DESC, q.id LIMIT ? OFFSET ?",
                (result, row) -> map(result), params.toArray());
        return PageResult.of(rows, page, size, total == null ? 0 : total);
    }

    public Optional<QuestionView> find(String id) {
        List<QuestionView> rows = jdbc.query(baseSelect() + " WHERE q.id = ?",
                (result, row) -> map(result), id);
        return rows.stream().findFirst();
    }

    @Transactional
    public QuestionView create(QuestionInput input, String actorId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(
                    id, subject_name, source_type, source_name, exam_year, question_number,
                    question_type, presentation_type, grading_mode, content_markdown,
                    standard_answer_json, analysis_markdown, difficulty, status,
                    parent_question_id, derivation_type, created_by, updated_by, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, 1)
                """, id, input.subject(), input.sourceType(), blank(input.sourceName()), input.examYear(),
                blank(input.questionNumber()), input.questionType(), input.presentationType(), input.gradingMode(),
                input.content(), json(input.standardAnswer()), input.analysis(), input.difficulty(),
                blank(input.parentQuestionId()), blank(input.derivationType()), actorId, actorId);
        replaceChildren(id, input, actorId);
        knowledgeStore.audit(actorId, "QUESTION_CREATED", "question", id, java.util.Map.of());
        return find(id).orElseThrow();
    }

    @Transactional
    public QuestionView update(String id, QuestionInput input, long expectedRevision, String actorId) {
        int changed = jdbc.update("""
                UPDATE question_resource
                   SET subject_name = ?, source_type = ?, source_name = ?, exam_year = ?, question_number = ?,
                       question_type = ?, presentation_type = ?, grading_mode = ?, content_markdown = ?,
                       standard_answer_json = ?, analysis_markdown = ?, difficulty = ?, parent_question_id = ?,
                       derivation_type = ?, updated_by = ?, revision = revision + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND revision = ?
                """, input.subject(), input.sourceType(), blank(input.sourceName()), input.examYear(),
                blank(input.questionNumber()), input.questionType(), input.presentationType(), input.gradingMode(),
                input.content(), json(input.standardAnswer()), input.analysis(), input.difficulty(),
                blank(input.parentQuestionId()), blank(input.derivationType()), actorId, id, expectedRevision);
        if (changed == 0) conflictOrMissing(id);
        replaceChildren(id, input, actorId);
        knowledgeStore.audit(actorId, "QUESTION_UPDATED", "question", id,
                java.util.Map.of("expectedRevision", expectedRevision));
        return find(id).orElseThrow();
    }

    @Transactional
    public QuestionView transition(String id, long expectedRevision, String from, String to,
                                   String actorId, String action, String comment) {
        int changed = jdbc.update("""
                UPDATE question_resource
                   SET status = ?, updated_by = ?, reviewed_by = CASE WHEN ? THEN ? ELSE reviewed_by END,
                       reviewed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE reviewed_at END,
                       review_comment = CASE WHEN ? THEN ? ELSE review_comment END,
                       revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND revision = ? AND status = ?
                """, to, actorId, isReview(action), actorId, isReview(action), isReview(action),
                comment, id, expectedRevision, from);
        if (changed == 0) conflictOrMissing(id);
        knowledgeStore.audit(actorId, action, "question", id,
                java.util.Map.of("from", from, "to", to, "comment", comment == null ? "" : comment));
        return find(id).orElseThrow();
    }

    private void replaceChildren(String id, QuestionInput input, String actorId) {
        jdbc.update("DELETE FROM question_resource_option WHERE question_id = ?", id);
        for (OptionInput option : input.options()) {
            jdbc.update("""
                    INSERT INTO question_resource_option(id, question_id, option_key, option_text, correct_option, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), id, option.key(), option.text(), option.correct(), option.sortOrder());
        }
        jdbc.update("DELETE FROM question_resource_knowledge WHERE question_id = ?", id);
        for (RelationInput relation : input.knowledgePoints()) {
            if (knowledgeStore.find(relation.knowledgePointId()).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "题目引用了不存在的知识点。");
            }
            jdbc.update("""
                    INSERT INTO question_resource_knowledge(question_id, knowledge_point_id, relation_role, sort_order, created_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, relation.knowledgePointId(), relation.role(), relation.sortOrder(), actorId);
        }
    }

    private QuestionView map(java.sql.ResultSet result) throws java.sql.SQLException {
        String id = result.getString("id");
        return new QuestionView(id, result.getString("subject_name"), result.getString("source_type"),
                result.getString("source_name"), (Integer) result.getObject("exam_year"),
                result.getString("question_number"), result.getString("question_type"),
                result.getString("presentation_type"), result.getString("grading_mode"),
                result.getString("content_markdown"), tree(result.getString("standard_answer_json")),
                result.getString("analysis_markdown"), result.getInt("difficulty"), result.getString("status"),
                result.getString("parent_question_id"), result.getString("derivation_type"),
                result.getString("created_by"), result.getString("creator_name"),
                result.getString("reviewed_by"), result.getString("review_comment"), result.getLong("revision"),
                options(id), relations(id));
    }

    private List<OptionView> options(String id) {
        return jdbc.query("""
                SELECT id, option_key, option_text, correct_option, sort_order
                  FROM question_resource_option WHERE question_id = ? ORDER BY sort_order, option_key
                """, (r, row) -> new OptionView(r.getString("id"), r.getString("option_key"),
                r.getString("option_text"), r.getBoolean("correct_option"), r.getInt("sort_order")), id);
    }

    private List<KnowledgeRelationView> relations(String id) {
        return jdbc.query("""
                SELECT k.id, k.code, k.name, qk.relation_role, qk.sort_order
                  FROM question_resource_knowledge qk
                  JOIN global_knowledge_point k ON k.id = qk.knowledge_point_id
                 WHERE qk.question_id = ? ORDER BY qk.sort_order, k.code
                """, (r, row) -> new KnowledgeRelationView(r.getString("id"), r.getString("code"),
                r.getString("name"), r.getString("relation_role"), r.getInt("sort_order")), id);
    }

    private void conflictOrMissing(String id) {
        if (find(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在。");
        throw new ResponseStatusException(HttpStatus.CONFLICT, "题目已被其他人修改，或状态已经变化，请重新加载。");
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value == null ? mapper.nullNode() : value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("答案无法序列化。", error); }
    }

    private JsonNode tree(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("答案数据损坏。", error); }
    }

    private static boolean isReview(String action) { return action.startsWith("QUESTION_REVIEW_"); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String baseSelect() {
        return """
                SELECT q.*, u.display_name creator_name
                  FROM question_resource q
                  LEFT JOIN app_user u ON u.id = q.created_by
                """;
    }

    private static SqlFilter filter(String query, String subject, String sourceType, Integer examYear,
                                    String questionType, String gradingMode, String status,
                                    String creator, String knowledge) {
        List<String> clauses = new ArrayList<>(); List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            clauses.add("(LOWER(q.content_markdown) LIKE ? OR LOWER(q.source_name) LIKE ? OR q.question_number LIKE ?)");
            String v = "%" + query.trim().toLowerCase() + "%"; params.add(v); params.add(v); params.add(v);
        }
        add(clauses, params, "q.subject_name", subject); add(clauses, params, "q.source_type", sourceType);
        if (examYear != null) { clauses.add("q.exam_year = ?"); params.add(examYear); }
        add(clauses, params, "q.question_type", questionType); add(clauses, params, "q.grading_mode", gradingMode);
        add(clauses, params, "q.status", status); add(clauses, params, "q.created_by", creator);
        if (knowledge != null && !knowledge.isBlank()) {
            clauses.add("EXISTS (SELECT 1 FROM question_resource_knowledge qk JOIN global_knowledge_point k "
                    + "ON k.id=qk.knowledge_point_id WHERE qk.question_id=q.id AND (k.id=? OR k.code=?))");
            params.add(knowledge.trim()); params.add(knowledge.trim());
        }
        return new SqlFilter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }
    private static void add(List<String> clauses, List<Object> params, String col, String value) {
        if (value != null && !value.isBlank()) { clauses.add(col + " = ?"); params.add(value.trim()); }
    }
    private record SqlFilter(String where, List<Object> params) {}
}

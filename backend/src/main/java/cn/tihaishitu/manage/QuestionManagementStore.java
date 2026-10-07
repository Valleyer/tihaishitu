package cn.tihaishitu.manage;

import cn.tihaishitu.learning.QuestionNumberFormatter;
import cn.tihaishitu.learning.QuestionNumberSort;
import cn.tihaishitu.learning.QuestionSearchQuery;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

@Repository
public class QuestionManagementStore {
    public record OptionView(String id, String key, String text, boolean correct, int sortOrder) {}
    public record KnowledgeRelationView(String id, String code, String name, String role, int sortOrder) {}
    public record QuestionView(
            String id, String subject, String sourceId, String sourceType, String sourceName,
            String sourceCanonicalName, Integer examYear,
            String questionNumber, String displayQuestionNumber, String questionType,
            String presentationType, String gradingMode,
            String content, String analysis, int difficulty, String status,
            String parentQuestionId, String derivationType, String createdBy, String creatorName,
            String reviewedBy, String reviewComment, long revision, Instant updatedAt,
            List<OptionView> options, List<KnowledgeRelationView> knowledgePoints) {}
    public record OptionInput(String key, String text, boolean correct, int sortOrder) {}
    public record RelationInput(String knowledgePointId, String role, int sortOrder) {}
    public record QuestionInput(
            String subject, String sourceId, String sourceType, String sourceName, Integer examYear, String questionNumber,
            String questionType, String presentationType, String gradingMode, String content,
            String analysis, int difficulty, String parentQuestionId,
            String derivationType, List<OptionInput> options, List<RelationInput> knowledgePoints) {}

    private final JdbcTemplate jdbc;
    private final KnowledgeManagementStore knowledgeStore;

    public QuestionManagementStore(JdbcTemplate jdbc, KnowledgeManagementStore knowledgeStore) {
        this.jdbc = jdbc;
        this.knowledgeStore = knowledgeStore;
    }

    public PageResult<QuestionView> search(String query, String subject, String sourceType, Integer examYear,
                                           String questionType, String gradingMode, String status,
                                           String creator, String knowledge, int page, int size) {
        SqlFilter filter = filter(query, subject, sourceType, examYear, questionType, gradingMode,
                status, creator, knowledge);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM question_resource q "
                        + "LEFT JOIN question_source s ON s.id=q.source_id " + filter.where(),
                Long.class, filter.params().toArray());
        List<Object> params = new ArrayList<>(filter.params());
        params.add(size); params.add(page * size);
        List<QuestionView> rows = jdbc.query(baseSelect() + filter.where()
                        + " ORDER BY " + orderBy() + " LIMIT ? OFFSET ?",
                (result, row) -> map(result), params.toArray());
        return PageResult.of(rows, page, size, total == null ? 0 : total);
    }

    /**
     * 管理端题目列表默认排序：来源 → 年份 → 题号自然排序 → 题目 ID。
     *
     * <p>与全平台题库 {@code LearningBrowseStore} 共用
     * {@link QuestionNumberSort}，避免两处题号排序规则漂移；审核中心也使用同一默认排序，
     * 不再以更新时间优先。排序键在 DB 级形成，因此分页结果稳定。</p>
     */
    private static String orderBy() {
        return QuestionNumberSort.orderBy("COALESCE(s.display_name,q.source_name,'')",
                "q.exam_year", "q.question_number", "q.id");
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
                    id, subject_name, source_id, source_type, source_name, exam_year, question_number,
                    question_type, presentation_type, grading_mode, content_markdown,
                    standard_answer_json, analysis_markdown, difficulty, status,
                    parent_question_id, derivation_type, created_by, updated_by, revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, 1)
                """, id, input.subject(), input.sourceId(), input.sourceType(), blank(input.sourceName()), input.examYear(),
                blank(input.questionNumber()), input.questionType(), input.presentationType(), input.gradingMode(),
                input.content(), null, input.analysis(), input.difficulty(),
                blank(input.parentQuestionId()), blank(input.derivationType()), actorId, actorId);
        replaceChildren(id, input, actorId);
        knowledgeStore.audit(actorId, "QUESTION_CREATED", "question", id, java.util.Map.of());
        return find(id).orElseThrow();
    }

    @Transactional
    public QuestionView update(String id, QuestionInput input, long expectedRevision, String actorId) {
        int changed = jdbc.update("""
                UPDATE question_resource
                   SET subject_name = ?, source_id = ?, source_type = ?, source_name = ?, exam_year = ?, question_number = ?,
                       question_type = ?, presentation_type = ?, grading_mode = ?, content_markdown = ?,
                       standard_answer_json = ?, analysis_markdown = ?, difficulty = ?, parent_question_id = ?,
                       derivation_type = ?, updated_by = ?, revision = revision + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND revision = ?
                """, input.subject(), input.sourceId(), input.sourceType(), blank(input.sourceName()), input.examYear(),
                blank(input.questionNumber()), input.questionType(), input.presentationType(), input.gradingMode(),
                input.content(), null, input.analysis(), input.difficulty(),
                blank(input.parentQuestionId()), blank(input.derivationType()), actorId, id, expectedRevision);
        if (changed == 0) conflictOrMissing(id);
        replaceChildren(id, input, actorId);
        bumpContainingBanks(id);
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
        bumpContainingBanks(id);
        knowledgeStore.audit(actorId, action, "question", id,
                java.util.Map.of("from", from, "to", to, "comment", comment == null ? "" : comment));
        return find(id).orElseThrow();
    }

    @Transactional
    public int bulkDelete(Set<String> requestedIds, String actorId) {
        Set<String> ids = new LinkedHashSet<>(requestedIds);
        if (ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "题目 ID 不能为空。");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<QuestionParent> rows = jdbc.query("SELECT id,parent_question_id FROM question_resource WHERE id IN ("
                        + placeholders + ")", (row, index) -> new QuestionParent(
                        row.getString("id"), row.getString("parent_question_id")), ids.toArray());
        if (rows.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "所选题目中有题目不存在，请刷新列表后重试。");
        }
        List<String> externalChildren = jdbc.query("SELECT id FROM question_resource WHERE parent_question_id IN ("
                        + placeholders + ") AND id NOT IN (" + placeholders + ")",
                (row, index) -> row.getString("id"), concat(ids, ids));
        if (!externalChildren.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "所选题目仍有未同时选择的派生题，必须将父题与全部子题一起删除。");
        }
        Integer activePractice = jdbc.queryForObject("SELECT COUNT(*) FROM learner_practice_session "
                        + "WHERE intent='wrong_review' AND status='active' AND source_question_id IN (" + placeholders + ")",
                Integer.class, ids.toArray());
        if (activePractice != null && activePractice > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "所选题目正在被错题练习使用，请先结束对应练习后再删除。");
        }
        Integer wrongHistory = jdbc.queryForObject("SELECT COUNT(*) FROM learner_wrong_question "
                        + "WHERE question_id IN (" + placeholders + ")", Integer.class, ids.toArray());
        if (wrongHistory != null && wrongHistory > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该题已存在用户错题历史，不能物理删除，请改为下架/归档。");
        }
        List<String> bankIds = jdbc.query("SELECT DISTINCT bank_id FROM question_bank_item WHERE question_id IN ("
                        + placeholders + ")", (row, index) -> row.getString("bank_id"), ids.toArray());
        jdbc.update("UPDATE learner_practice_session SET source_question_id=NULL WHERE status='ended' "
                + "AND source_question_id IN (" + placeholders + ")", ids.toArray());
        jdbc.update("DELETE FROM learner_question_mastery WHERE question_id IN (" + placeholders + ")", ids.toArray());

        Map<String, String> parents = new LinkedHashMap<>();
        rows.forEach(row -> parents.put(row.id(), row.parentId()));
        Set<String> remaining = new LinkedHashSet<>(ids);
        int deleted = 0;
        while (!remaining.isEmpty()) {
            String leaf = remaining.stream().filter(candidate -> remaining.stream()
                    .noneMatch(child -> candidate.equals(parents.get(child)))).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "题目父子关系存在循环，无法删除。"));
            deleted += jdbc.update("DELETE FROM question_resource WHERE id=?", leaf);
            remaining.remove(leaf);
        }
        bankIds.forEach(bankId -> jdbc.update("UPDATE question_bank SET revision=revision+1, "
                + "updated_at=CURRENT_TIMESTAMP WHERE id=?", bankId));
        knowledgeStore.audit(actorId, "QUESTION_BULK_DELETED", "question_batch", UUID.randomUUID().toString(),
                Map.of("questionIds", ids, "count", deleted));
        return deleted;
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

    private void bumpContainingBanks(String questionId) {
        jdbc.update("""
                UPDATE question_bank SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id IN (SELECT bank_id FROM question_bank_item WHERE question_id = ?)
                """, questionId);
    }

    private QuestionView map(java.sql.ResultSet result) throws java.sql.SQLException {
        String id = result.getString("id");
        Integer examYear = (Integer) result.getObject("exam_year");
        String questionNumber = result.getString("question_number");
        return new QuestionView(id, result.getString("subject_name"), result.getString("source_id"),
                result.getString("resolved_source_type"), result.getString("resolved_source_name"),
                result.getString("source_canonical_name"), examYear,
                questionNumber,
                // UI 一律使用 displayQuestionNumber；原始 questionNumber 只作为数据事实与编辑事实。
                // 例如 examYear=2020 + raw "2020-7" 显示为 "7"，不会拼成 "2020-2020-7"。
                QuestionNumberFormatter.display(questionNumber, examYear),
                result.getString("question_type"),
                result.getString("presentation_type"), result.getString("grading_mode"),
                result.getString("content_markdown"), result.getString("analysis_markdown"),
                result.getInt("difficulty"), result.getString("status"),
                result.getString("parent_question_id"), result.getString("derivation_type"),
                result.getString("created_by"), result.getString("creator_name"),
                result.getString("reviewed_by"), result.getString("review_comment"), result.getLong("revision"),
                result.getTimestamp("updated_at").toInstant(),
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

    private static boolean isReview(String action) { return action.startsWith("QUESTION_REVIEW_"); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Object[] concat(Set<String> first, Set<String> second) {
        List<Object> values = new ArrayList<>(first);
        values.addAll(second);
        return values.toArray();
    }
    private static String baseSelect() {
        return """
                SELECT q.*, COALESCE(s.source_type,q.source_type) resolved_source_type,
                       COALESCE(s.display_name,q.source_name,'全服题库') resolved_source_name,
                       COALESCE(s.canonical_name,q.source_name) source_canonical_name,
                       COALESCE(l.display_name, u.display_name) creator_name
                  FROM question_resource q
                  LEFT JOIN question_source s ON s.id=q.source_id
                  LEFT JOIN learner_account l ON l.id = q.created_by
                  LEFT JOIN app_user u ON u.id = q.created_by
                """;
    }

    private static SqlFilter filter(String query, String subject, String sourceType, Integer examYear,
                                    String questionType, String gradingMode, String status,
                                    String creator, String knowledge) {
        List<String> clauses = new ArrayList<>(); List<Object> params = new ArrayList<>();
        // 解析规则与全平台题库共用 QuestionSearchQuery，两个入口不能漂移；
        // SQL 过滤片段各自维护，因为管理端查全状态、Learner 端只查 published。
        QuestionSearchQuery search = QuestionSearchQuery.parse(query);
        if (search.keyword() != null) {
            String pattern = search.likePattern();
            List<String> alternatives = new ArrayList<>();
            alternatives.add("(LOWER(q.content_markdown) LIKE ? OR LOWER(COALESCE(s.display_name,q.source_name)) LIKE ?"
                    + " OR LOWER(q.question_number) LIKE ?)");
            params.add(pattern); params.add(pattern); params.add(pattern);
            if (search.structured()) {
                // “2020-7” 是对 broad search 的**并列**命中方式：命中 exam_year=2020 且题号为 7
                // （含历史写法 "2020-7"）。不能与 broad search 做 AND，否则标准写法会被排除。
                alternatives.add("(q.exam_year = ? AND LOWER(TRIM(q.question_number)) IN (?, ?))");
                params.add(search.year());
                params.add(search.number().toLowerCase());
                params.add(search.yearNumberLiteral());
            }
            clauses.add("(" + String.join(" OR ", alternatives) + ")");
        }
        add(clauses, params, "q.subject_name", subject); add(clauses, params, "COALESCE(s.source_type,q.source_type)", sourceType);
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
    private record QuestionParent(String id, String parentId) {}
}

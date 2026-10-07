package cn.tihaishitu.learning;

import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.manage.PageResult;
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
    private final LearnerQuestionProgressStore questionProgress;
    public LearningBrowseStore(JdbcTemplate jdbc, ObjectMapper mapper,
                               LearnerQuestionProgressStore questionProgress) {
        this.jdbc = jdbc; this.mapper = mapper; this.questionProgress = questionProgress;
    }

    public List<Map<String, Object>> books(String learnerId) {
        return jdbc.query("""
                SELECT b.id, b.name, b.description, b.revision,
                       (SELECT COUNT(DISTINCT bk.knowledge_point_id)
                          FROM question_bank_knowledge bk
                          JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id
                         WHERE bk.bank_id=b.id AND k.status='active' AND %s) knowledge_count,
                       (SELECT COUNT(DISTINCT q.id)
                          FROM question_bank_knowledge bk
                          JOIN question_resource_knowledge qk ON qk.knowledge_point_id=bk.knowledge_point_id
                          JOIN question_resource q ON q.id=qk.question_id
                         WHERE bk.bank_id=b.id AND qk.relation_role IN ('core','auxiliary') AND q.status='published'
                           AND q.parent_question_id IS NULL
                           AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')) question_count
                  FROM question_bank b
                  JOIN learner_selected_book selected ON selected.bank_id=b.id
                 WHERE selected.learner_id=? AND b.enabled = TRUE
                   AND EXISTS (SELECT 1 FROM question_bank_knowledge visible_bk
                               JOIN global_knowledge_point k ON k.id=visible_bk.knowledge_point_id
                               WHERE visible_bk.bank_id=b.id AND k.status='active' AND %s)
                 ORDER BY b.created_at, b.id
                """.formatted(TrainableKnowledge.exists("k"), TrainableKnowledge.exists("k")), (result, row) -> ordered(
                "id", result.getString("id"), "name", result.getString("name"),
                "description", result.getString("description"), "revision", result.getLong("revision"),
                "knowledgePointCount", result.getInt("knowledge_count"),
                "questionCount", result.getInt("question_count")), learnerId);
    }

    public Map<String, Object> book(String id, String learnerId) {
        Map<String, Object> book = jdbc.query("""
                SELECT b.id,b.name,b.description,b.revision,
                       (SELECT COUNT(DISTINCT bk.knowledge_point_id)
                          FROM question_bank_knowledge bk JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id
                         WHERE bk.bank_id=b.id AND k.status='active' AND %s) knowledge_count,
                       (SELECT COUNT(DISTINCT q.id)
                          FROM question_bank_knowledge bk
                          JOIN question_resource_knowledge qk ON qk.knowledge_point_id=bk.knowledge_point_id
                          JOIN question_resource q ON q.id=qk.question_id
                         WHERE bk.bank_id=b.id AND qk.relation_role IN ('core','auxiliary') AND q.status='published'
                           AND q.parent_question_id IS NULL
                           AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')) question_count
                  FROM question_bank b
                  JOIN learner_selected_book selected ON selected.bank_id=b.id AND selected.learner_id=?
                 WHERE b.id=? AND b.enabled=TRUE
                """.formatted(TrainableKnowledge.exists("k")),
                (result, row) -> ordered("id", result.getString("id"), "name", result.getString("name"),
                        "description", result.getString("description"), "revision", result.getLong("revision"),
                        "knowledgePointCount", result.getInt("knowledge_count"),
                        "questionCount", result.getInt("question_count")), learnerId, id)
                .stream().findFirst().orElseThrow(() -> missing("文集不存在或已停用。"));
        List<Map<String, Object>> chapters = jdbc.query("""
                SELECT id, chapter_code, name, description, sort_order
                  FROM question_bank_chapter WHERE bank_id = ? ORDER BY sort_order, id
                """, (result, row) -> ordered("id", result.getString("id"),
                "code", result.getString("chapter_code"), "name", result.getString("name"),
                "description", result.getString("description"), "sortOrder", result.getInt("sort_order")), id);
        Map<String, List<Map<String, Object>>> points = new LinkedHashMap<>();
        jdbc.query("""
                SELECT bk.chapter_id, k.id, k.code, k.name, k.subject_name, k.section_name,
                       k.chapter_name, k.description, k.explanation, bk.sort_order
                 FROM question_bank_knowledge bk JOIN global_knowledge_point k ON k.id = bk.knowledge_point_id
                 WHERE bk.bank_id = ? AND k.status = 'active' AND %s ORDER BY bk.sort_order, k.id
                """.formatted(TrainableKnowledge.exists("k")), (RowCallbackHandler) result -> points.computeIfAbsent(result.getString("chapter_id"), ignored -> new ArrayList<>())
                .add(knowledge(result)), id);
        Map<String, int[]> chapterCounts = new HashMap<>();
        jdbc.query("""
                SELECT c.id,
                       COUNT(DISTINCT CASE WHEN k.status='active' THEN bk.knowledge_point_id END) knowledge_count,
                       COUNT(DISTINCT CASE WHEN k.status='active' AND %s THEN bk.knowledge_point_id END) trainable_count,
                       COUNT(DISTINCT CASE WHEN q.status='published' AND q.parent_question_id IS NULL
                                                AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                                           THEN q.id END) question_count
                  FROM question_bank_chapter c
                  LEFT JOIN question_bank_knowledge bk ON bk.chapter_id=c.id AND bk.bank_id=c.bank_id
                  LEFT JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id
                  LEFT JOIN question_resource_knowledge qk ON qk.knowledge_point_id=k.id
                            AND qk.relation_role IN ('core','auxiliary')
                  LEFT JOIN question_resource q ON q.id=qk.question_id
                 WHERE c.bank_id=?
                 GROUP BY c.id
                """.formatted(TrainableKnowledge.exists("k")), (RowCallbackHandler) result -> chapterCounts.put(
                result.getString("id"), new int[]{result.getInt("knowledge_count"),
                        result.getInt("trainable_count"), result.getInt("question_count")}), id);
        List<Map<String, Object>> flat = chapters.stream().map(chapter -> {
            Map<String, Object> node = new LinkedHashMap<>(chapter);
            node.put("knowledgePoints", points.getOrDefault(chapter.get("id"), List.of()));
            int[] counts = chapterCounts.getOrDefault(chapter.get("id"), new int[3]);
            node.put("knowledgePointCount", counts[0]);
            node.put("trainableKnowledgePointCount", counts[1]);
            node.put("publishedQuestionCount", counts[2]);
            return node;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>(book);
        result.put("chapters", flat);
        return result;
    }

    public Map<String, Object> knowledge(String id, String learnerId) {
        return jdbc.query("""
                SELECT id, code, name, subject_name, section_name, chapter_name, description, explanation, revision
                  FROM global_knowledge_point k WHERE id = ? AND status = 'active'
                   AND %s
                   AND EXISTS (SELECT 1 FROM question_bank_knowledge bk
                               JOIN question_bank b ON b.id=bk.bank_id AND b.enabled=TRUE
                               JOIN learner_selected_book selected ON selected.bank_id=b.id
                              WHERE bk.knowledge_point_id=k.id AND selected.learner_id=?)
                """.formatted(TrainableKnowledge.exists("k")), (result, row) -> {
            Map<String, Object> value = knowledge(result);
            value.put("revision", result.getLong("revision"));
            value.put("books", jdbc.query("""
                    SELECT b.id, b.name, c.id chapter_id, c.name chapter_name FROM question_bank_knowledge bk
                    JOIN question_bank b ON b.id = bk.bank_id AND b.enabled=TRUE
                    JOIN question_bank_chapter c ON c.id=bk.chapter_id
                    JOIN learner_selected_book selected ON selected.bank_id=b.id
                    WHERE selected.learner_id=? AND bk.knowledge_point_id=? ORDER BY b.name
                    """, (books, index) -> ordered("id", books.getString("id"), "name", books.getString("name"),
                            "chapterId", books.getString("chapter_id"), "chapterName", books.getString("chapter_name")), learnerId, id));
            return value;
        }, id, learnerId).stream().findFirst().orElseThrow(() -> missing("知识点不存在或当前不在学习范围。"));
    }

    public Map<String, Object> guide(String id, String learnerId) {
        ensureTrainable(id, learnerId);
        return jdbc.query("SELECT content_markdown,revision,updated_at FROM knowledge_point_guide WHERE knowledge_point_id=?",
                (rs,row)->ordered("knowledgePointId",id,"contentMarkdown",rs.getString(1),
                        "revision",rs.getLong(2),"updatedAt",rs.getTimestamp(3).toInstant()),id)
                .stream().findFirst().orElse(ordered("knowledgePointId",id,"contentMarkdown",null,"revision",0L));
    }

    public Map<String, Object> neighbors(String id, String bookId, String chapterId, String learnerId) {
        ensureTrainable(id, learnerId);
        Integer context = jdbc.queryForObject("""
                SELECT COUNT(*) FROM question_bank_knowledge bk
                JOIN learner_selected_book selected ON selected.bank_id=bk.bank_id AND selected.learner_id=?
                WHERE bk.bank_id=? AND bk.chapter_id=? AND bk.knowledge_point_id=?
                """, Integer.class, learnerId, bookId, chapterId, id);
        if (context == null || context == 0) throw missing("知识点不属于指定的文集章节。");
        List<Map<String,Object>> ordered = jdbc.query("""
                SELECT k.id,k.name,bk.chapter_id,c.name chapter_name
                  FROM question_bank_chapter c
                  JOIN question_bank_knowledge bk ON bk.chapter_id=c.id AND bk.bank_id=c.bank_id
                  JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id AND k.status='active'
                 WHERE c.bank_id=? AND %s
                 ORDER BY c.sort_order,c.id,bk.sort_order,k.id
                """.formatted(TrainableKnowledge.exists("k")),(rs,row)->ordered("id",rs.getString("id"),
                "name",rs.getString("name"),"chapterId",rs.getString("chapter_id"),
                "chapterName",rs.getString("chapter_name")),bookId);
        int index=-1;for(int i=0;i<ordered.size();i++)if(id.equals(ordered.get(i).get("id"))){index=i;break;}
        return ordered("previous",index>0?ordered.get(index-1):null,"next",index>=0&&index+1<ordered.size()?ordered.get(index+1):null);
    }

    public PageResult<Map<String, Object>> knowledgePoints(String learnerId, String query, String bookId,
                                                            String chapterId, String subject, int page, int size) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        params.add(learnerId);
        clauses.add("k.status = 'active'");
        if (query != null && !query.isBlank()) {
            clauses.add("LOWER(k.name) LIKE ?");
            String value = "%" + query.trim().toLowerCase() + "%";
            params.add(value);
        }
        if (bookId != null && !bookId.isBlank()) { clauses.add("bk.bank_id = ?"); params.add(bookId); }
        if (chapterId != null && !chapterId.isBlank()) { clauses.add("bk.chapter_id = ?"); params.add(chapterId); }
        if (subject != null && !subject.isBlank()) { clauses.add("k.subject_name = ?"); params.add(subject); }
        clauses.add(TrainableKnowledge.exists("k"));
        String from = """
                  FROM global_knowledge_point k
                  JOIN question_bank_knowledge bk ON bk.knowledge_point_id=k.id
                  JOIN question_bank b ON b.id=bk.bank_id AND b.enabled=TRUE
                  JOIN learner_selected_book selected ON selected.bank_id=b.id AND selected.learner_id=?
                  JOIN question_bank_chapter c ON c.id=bk.chapter_id
                 WHERE %s
                """.formatted(String.join(" AND ", clauses));
        Long total = jdbc.queryForObject("SELECT COUNT(DISTINCT k.id) " + from, Long.class, params.toArray());
        List<Object> contentParams = new ArrayList<>(params);
        contentParams.add(size); contentParams.add(page * size);
        List<Map<String, Object>> content = jdbc.query("""
                SELECT k.id,k.code,k.name,k.subject_name,k.section_name,k.chapter_name,
                       MIN(b.id) book_id,MIN(b.name) book_name,MIN(c.id) chapter_id,MIN(c.name) catalog_chapter,
                       (SELECT COUNT(DISTINCT q.id) FROM question_resource_knowledge qk
                         JOIN question_resource q ON q.id=qk.question_id
                        WHERE qk.knowledge_point_id=k.id AND qk.relation_role IN ('core','auxiliary')
                          AND q.status='published'
                          AND q.parent_question_id IS NULL
                          AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')) published_count
                  %s
                 GROUP BY k.id,k.code,k.name,k.subject_name,k.section_name,k.chapter_name,k.sort_order
                 ORDER BY k.sort_order,k.code
                 LIMIT ? OFFSET ?
                """.formatted(from), (row, index) -> ordered(
                "id", row.getString("id"), "code", row.getString("code"), "name", row.getString("name"),
                "subject", row.getString("subject_name"), "section", row.getString("section_name"),
                "chapter", row.getString("chapter_name"), "bookId", row.getString("book_id"),
                "bookName", row.getString("book_name"), "chapterId", row.getString("chapter_id"),
                "catalogChapter", row.getString("catalog_chapter"),
                "publishedQuestionCount", row.getInt("published_count")), contentParams.toArray());
        return PageResult.of(content, page, size, total == null ? 0 : total);
    }

    public List<String> knowledgeSubjects(String learnerId) {
        return jdbc.query("""
                SELECT DISTINCT k.subject_name
                  FROM learner_selected_book selected
                  JOIN question_bank b ON b.id=selected.bank_id AND b.enabled=TRUE
                  JOIN question_bank_knowledge bk ON bk.bank_id=b.id
                  JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id
                 WHERE selected.learner_id=? AND k.status='active' AND %s
                 ORDER BY k.subject_name
                """.formatted(TrainableKnowledge.exists("k")),
                (result, row) -> result.getString("subject_name"), learnerId);
    }

    public List<Map<String, Object>> questionsForKnowledge(String id, String learnerId) {
        ensureTrainable(id, learnerId);
        List<Map<String, Object>> questions = jdbc.query("""
                SELECT DISTINCT q.id, q.subject_name, COALESCE(s.source_type,q.source_type) source_type,
                       COALESCE(s.display_name,q.source_name,'全服题库') source_name, q.exam_year,
                       q.question_number, q.question_type,
                       q.presentation_type, q.grading_mode, q.content_markdown, q.analysis_markdown,
                       q.standard_answer_json, q.difficulty, q.revision
                  FROM question_resource q
                  LEFT JOIN question_source s ON s.id=q.source_id
                  JOIN question_resource_knowledge qk ON qk.question_id = q.id
                 WHERE qk.knowledge_point_id = ? AND q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                 ORDER BY q.id
                """, (result, row) -> question(result), id);
        questions.forEach(question -> question.put("knowledgePoints", questionKnowledge((String) question.get("id"))));
        Map<String, LearnerQuestionProgressStore.QuestionProgress> progress = questionProgress.latestGradedForQuestions(
                learnerId, id, questions.stream().map(question -> (String) question.get("id")).toList());
        questions.forEach(question -> {
            var latest = progress.get((String) question.get("id"));
            String assessment = latest == null ? null : latest.assessment();
            question.put("learnerQuestionStatus", assessment == null ? "unseen"
                    : "correct".equals(assessment) ? "mastered" : "needs_review");
            question.put("latestAssessment", assessment);
            question.put("lastGradedAt", latest == null ? null : latest.answeredAt());
        });
        return questions;
    }

    public Map<String, Object> question(String id, String learnerId) {
        Map<String, Object> value = jdbc.query("""
                SELECT q.id, q.subject_name, COALESCE(s.source_type,q.source_type) source_type,
                       COALESCE(s.display_name,q.source_name,'全服题库') source_name, q.exam_year, q.question_number,
                       q.question_type, q.presentation_type,
                       q.grading_mode, q.content_markdown, q.analysis_markdown, q.standard_answer_json,
                       q.difficulty, q.revision
                  FROM question_resource q LEFT JOIN question_source s ON s.id=q.source_id
                 WHERE q.id = ? AND q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                   AND EXISTS (
                       SELECT 1 FROM question_resource_knowledge qk
                       JOIN question_bank_knowledge bk ON bk.knowledge_point_id=qk.knowledge_point_id
                       JOIN learner_selected_book selected ON selected.bank_id=bk.bank_id
                       JOIN question_bank b ON b.id=bk.bank_id AND b.enabled=TRUE
                       WHERE qk.question_id=q.id AND selected.learner_id=?
                   )
                """, (result, row) -> question(result), id, learnerId).stream().findFirst()
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

    private void ensureTrainable(String id, String learnerId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM global_knowledge_point k
                 WHERE k.id=? AND k.status='active' AND %s
                   AND EXISTS (SELECT 1 FROM question_bank_knowledge bk
                               JOIN question_bank b ON b.id=bk.bank_id AND b.enabled=TRUE
                               JOIN learner_selected_book selected ON selected.bank_id=b.id
                              WHERE bk.knowledge_point_id=k.id AND selected.learner_id=?)
                """.formatted(TrainableKnowledge.exists("k")), Integer.class, id, learnerId);
        if (count == null || count == 0) throw missing("知识点不存在或当前不可学习。");
    }

    private Map<String, Object> question(java.sql.ResultSet result) throws java.sql.SQLException {
        Integer examYear = result.getObject("exam_year", Integer.class);
        String questionNumber = result.getString("question_number");
        return ordered("id", result.getString("id"), "subject", result.getString("subject_name"),
                "sourceType", result.getString("source_type"), "sourceName", result.getString("source_name"),
                "examYear", examYear,
                "questionNumber", questionNumber,
                // UI 只使用 displayQuestionNumber；原始 questionNumber 保留为数据事实。
                "displayQuestionNumber", QuestionNumberFormatter.display(questionNumber, examYear),
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

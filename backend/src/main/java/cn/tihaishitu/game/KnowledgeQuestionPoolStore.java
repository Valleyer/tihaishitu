package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.catalog.QuestionAnswerDeriver;
import cn.tihaishitu.learning.KnowledgeQuestionCoveragePolicy;
import cn.tihaishitu.learning.TrainableKnowledge;
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
    private final QuestionAnswerDeriver answerDeriver;

    public KnowledgeQuestionPoolStore(JdbcTemplate jdbc, QuestionAnswerDeriver answerDeriver) {
        this.jdbc = jdbc;
        this.answerDeriver = answerDeriver;
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
     * Book-level 正式题池：Selected Book(s) 覆盖到的**全部**去重 Formal Question。
     *
     * <p>与 KnowledgePoint 专项不同，这里不先选知识点，而是直接把整本书（或整批 selected books）
     * 覆盖到的正式题收集成一个池子，再由调用方等概率随机抽题。语义：</p>
     *
     * <pre>
     * selected Book(s)
     *   → 这些 Book 下全部 active 的 KnowledgePoint
     *   → 与这些 KnowledgePoint 有关系的 published Formal Parent Question
     *   → core + auxiliary 都算覆盖
     *   → 按 question_id DISTINCT 去重（一题关联多个 KP、或同时属于多本 selected Book，都只出现一次）
     * </pre>
     *
     * <p>selected Book 为空时沿用其他学习链路的约定：使用全部 enabled Book
     * （与 {@link #bookScope} 的解析一致）。</p>
     */
    public List<QuestionDto> candidatesForBooks(Set<String> requestedBookIds,
                                                Set<String> excludedQuestionIds) {
        List<String> bookIds = enabledBookIds(requestedBookIds);
        if (bookIds.isEmpty()) return List.of();
        Set<String> excluded = excludedQuestionIds == null ? Set.of() : excludedQuestionIds;
        List<Object> args = new ArrayList<>(bookIds);
        StringBuilder exclusion = new StringBuilder();
        if (!excluded.isEmpty()) {
            exclusion.append(" AND q.id NOT IN (").append(placeholders(excluded.size())).append(")");
            args.addAll(excluded);
        }
        List<QuestionRow> rows = jdbc.query("""
                SELECT DISTINCT q.id, q.subject_name, COALESCE(s.source_type,q.source_type) source_type,
                       COALESCE(s.display_name,q.source_name) source_name, q.exam_year, q.question_number,
                       q.question_type, q.presentation_type, q.grading_mode, q.content_markdown,
                       q.analysis_markdown, q.difficulty, q.stem_image_id
                  FROM question_resource q
                  LEFT JOIN question_source s ON s.id=q.source_id
                  JOIN question_resource_knowledge qk ON qk.question_id = q.id
                  JOIN global_knowledge_point k ON k.id = qk.knowledge_point_id
                  JOIN question_bank_knowledge bk ON bk.knowledge_point_id = k.id
                 WHERE bk.bank_id IN (%s)
                   AND k.status = 'active'
                   AND q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                   %s
                 ORDER BY q.id
                """.formatted(placeholders(bookIds.size()), exclusion),
                (result, row) -> questionRow(result), args.toArray());
        return questions(rows);
    }

    /**
     * 按题目 ID 取回候选正式题（用于补救重做同一道题等按 ID 发题场景）。
     * 只保留仍是 published 正式父题的记录。
     */
    public List<QuestionDto> candidatesForQuestions(Set<String> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return List.of();
        List<QuestionRow> rows = jdbc.query("""
                SELECT q.id, q.subject_name, COALESCE(s.source_type,q.source_type) source_type,
                       COALESCE(s.display_name,q.source_name) source_name, q.exam_year, q.question_number,
                       q.question_type, q.presentation_type, q.grading_mode, q.content_markdown,
                       q.analysis_markdown, q.difficulty, q.stem_image_id
                  FROM question_resource q
                  LEFT JOIN question_source s ON s.id=q.source_id
                 WHERE q.id IN (%s)
                   AND q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                 ORDER BY q.id
                """.formatted(placeholders(questionIds.size())),
                (result, row) -> questionRow(result), questionIds.toArray());
        return questions(rows);
    }

    /**
     * 为一道题解析稳定的 target KnowledgePoint：只考虑传入 scope 内的关联知识点，
     * 优先 relation_role = 'core'，再按 sort_order、knowledge_point_id 取第一个；
     * 没有 core 时取 auxiliary 中 sort_order 最小的一个。
     *
     * <p>同一道题每次解析结果一致，不做随机，避免同一题在不同时间强化不同知识点。</p>
     */
    public Optional<String> targetKnowledgePointFor(String questionId, Set<String> scopeKnowledgePointIds) {
        if (questionId == null) return Optional.empty();
        Optional<String> picked = pickTargetPoint(questionId, scopeKnowledgePointIds, true);
        return picked.isPresent() ? picked : pickTargetPoint(questionId, scopeKnowledgePointIds, false);
    }

    /** core 优先、再按 sort_order / knowledge_point_id 的最小关联知识点。 */
    private Optional<String> pickTargetPoint(String questionId, Set<String> scopeKnowledgePointIds, boolean scoped) {
        StringBuilder scope = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(questionId);
        if (scoped) {
            if (scopeKnowledgePointIds == null || scopeKnowledgePointIds.isEmpty()) return Optional.empty();
            scope.append(" AND qk.knowledge_point_id IN (")
                    .append(placeholders(scopeKnowledgePointIds.size())).append(")");
            args.addAll(scopeKnowledgePointIds);
        }
        return jdbc.query("""
                SELECT qk.knowledge_point_id
                  FROM question_resource_knowledge qk
                  JOIN global_knowledge_point k ON k.id = qk.knowledge_point_id
                 WHERE qk.question_id = ?
                   AND k.status = 'active'
                   %s
                 ORDER BY CASE WHEN qk.relation_role = 'core' THEN 0 ELSE 1 END, qk.sort_order, qk.knowledge_point_id
                 LIMIT 1
                """.formatted(scope), (result, row) -> result.getString(1), args.toArray())
                .stream().findFirst();
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
                SELECT q.id, q.subject_name, COALESCE(s.source_type,q.source_type) source_type,
                       COALESCE(s.display_name,q.source_name) source_name, q.exam_year, q.question_number,
                       q.question_type, q.presentation_type, q.grading_mode, q.content_markdown,
                       q.analysis_markdown, q.difficulty, q.stem_image_id
                  FROM question_resource q
                  LEFT JOIN question_source s ON s.id=q.source_id
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
                SELECT q.subject_name, COALESCE(s.display_name,q.source_name) source_name,
                       q.exam_year, q.question_number
                  FROM question_resource q LEFT JOIN question_source s ON s.id=q.source_id WHERE q.id = ?
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

    private record LoadedOptions(Map<String, Map<String, String>> texts,
                                 Map<String, List<QuestionAnswerDeriver.Option>> facts) {}

    private LoadedOptions loadOptions(List<String> questionIds) {
        Map<String, Map<String, String>> texts = new LinkedHashMap<>();
        Map<String, List<QuestionAnswerDeriver.Option>> facts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT question_id, option_key, option_text, correct_option, sort_order
                  FROM question_resource_option
                 WHERE question_id IN (%s)
                 ORDER BY question_id, sort_order, option_key
                """.formatted(placeholders(questionIds.size())), (RowCallbackHandler) result -> {
            String questionId = result.getString("question_id");
            texts.computeIfAbsent(questionId, ignored -> new LinkedHashMap<>())
                    .put(result.getString("option_key"), result.getString("option_text"));
            facts.computeIfAbsent(questionId, ignored -> new ArrayList<>())
                    .add(new QuestionAnswerDeriver.Option(result.getString("option_key"),
                            result.getBoolean("correct_option"), result.getInt("sort_order")));
        }, questionIds.toArray());
        return new LoadedOptions(texts, facts);
    }

    private List<QuestionDto> questions(List<QuestionRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> questionIds = rows.stream().map(QuestionRow::id).toList();
        LoadedOptions options = loadOptions(questionIds);
        Map<String, List<QuestionKnowledge>> knowledge = questionKnowledge(questionIds);
        return rows.stream().map(row -> new QuestionDto(
                row.id(), row.subject(), row.sourceType(), value(row.sourceName(), "全服题库"),
                row.presentationType(), row.questionType(), row.presentationType(), row.gradingMode(),
                row.content(), options.texts().getOrDefault(row.id(), Map.of()),
                answerDeriver.derive(row.questionType(), options.facts().getOrDefault(row.id(), List.of())), row.analysis(),
                List.of(), List.of(), row.difficulty(), 3, List.of(),
                knowledge.getOrDefault(row.id(), List.of()).stream().map(QuestionKnowledge::knowledgePointId).toList(),
                cn.tihaishitu.questionimage.QuestionImageUrls.url(row.stemImageId()),
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
                result.getString("content_markdown"),
                result.getString("analysis_markdown"), result.getInt("difficulty"), result.getString("stem_image_id"));
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
            String presentationType, String gradingMode, String content,
            String analysis, int difficulty, String stemImageId) {}
}

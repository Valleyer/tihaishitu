package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
public class LearnerPracticeStore {
    public record Session(String id, String learnerId, String intent, String targetKnowledgePointId,
                          String targetBookId, String targetChapterId, String currentKnowledgePointId,
                          String sourceQuestionId, String currentAttemptId, String status, long revision,
                          Instant createdAt, Instant updatedAt, Instant endedAt) {}
    public record WrongQuestion(String questionId, String targetKnowledgePointId, String knowledgePointName,
                                String contentMarkdown, String subjectName, Integer examYear,
                                String questionNumber, Instant lastGradedAt, boolean available,
                                String unavailableReason, List<KnowledgePointTag> knowledgePoints) {
        public WrongQuestion {
            knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        }

        public WrongQuestion withKnowledgePoints(List<KnowledgePointTag> tags) {
            return new WrongQuestion(questionId, targetKnowledgePointId, knowledgePointName, contentMarkdown,
                    subjectName, examYear, questionNumber, lastGradedAt, available, unavailableReason, tags);
        }

        /** 动态生成的真题展示标签，例如 2022年考研数学一真题。 */
        public String examLabel() {
            return KnowledgeQuestionExamLabel.generate(subjectName, examYear);
        }

        /** UI 显示题号（已剥离同年份前缀）；原始 questionNumber 仍作为数据事实保留。 */
        public String displayQuestionNumber() {
            return QuestionNumberFormatter.display(questionNumber, examYear);
        }
    }

    private final JdbcTemplate jdbc;

    public LearnerPracticeStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(String id, String learnerId, String intent, String targetId,
                       String sourceQuestionId, Set<String> scope) {
        jdbc.update("""
                INSERT INTO learner_practice_session(
                    id,learner_id,intent,target_knowledge_point_id,source_question_id,status,revision)
                VALUES (?,?,?,?,?,'active',1)
                """, id, learnerId, intent, targetId, sourceQuestionId);
        for (String pointId : scope) {
            jdbc.update("""
                    INSERT INTO learner_practice_scope(session_id,knowledge_point_id) VALUES (?,?)
                    """, id, pointId);
        }
    }

    public void createChapter(String id,String learnerId,String bookId,String chapterId,String currentPointId,Set<String> scope){
        jdbc.update("""
                INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,
                    target_book_id,target_chapter_id,current_knowledge_point_id,status,revision)
                VALUES (?,?,'chapter_drill',NULL,?,?,?,'active',1)
                """,id,learnerId,bookId,chapterId,currentPointId);
        for(String pointId:scope)jdbc.update("INSERT INTO learner_practice_scope(session_id,knowledge_point_id) VALUES (?,?)",id,pointId);
    }

    public List<String> chapterKnowledgePoints(String learnerId,String bookId,String chapterId){
        return jdbc.query("""
                SELECT bk.knowledge_point_id FROM question_bank_knowledge bk
                JOIN learner_selected_book selected ON selected.bank_id=bk.bank_id AND selected.learner_id=?
                JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id AND k.status='active'
                WHERE bk.bank_id=? AND bk.chapter_id=? AND %s ORDER BY bk.sort_order,bk.knowledge_point_id
                """.formatted(TrainableKnowledge.exists("k")),(rs,row)->rs.getString(1),learnerId,bookId,chapterId);
    }

    /**
     * 整本文集的 Chapter → KnowledgePoint 归属，一次查询取回。
     * 判定条件与 chapterKnowledgePoints 完全一致（在所选文集内、知识点 active、存在可练正式题），
     * 供书级批量 availability 使用，避免逐章重复查询。
     */
    public record ChapterMembership(String chapterId, String knowledgePointId) {}

    public List<ChapterMembership> bookChapterMemberships(String learnerId, String bookId) {
        return jdbc.query("""
                SELECT bk.chapter_id,bk.knowledge_point_id FROM question_bank_knowledge bk
                JOIN learner_selected_book selected ON selected.bank_id=bk.bank_id AND selected.learner_id=?
                JOIN global_knowledge_point k ON k.id=bk.knowledge_point_id AND k.status='active'
                WHERE bk.bank_id=? AND %s ORDER BY bk.chapter_id,bk.sort_order,bk.knowledge_point_id
                """.formatted(TrainableKnowledge.exists("k")),
                (rs,row)->new ChapterMembership(rs.getString("chapter_id"),rs.getString("knowledge_point_id")),
                learnerId,bookId);
    }

    /**
     * 整本文集一次批量取回每个 Chapter 下“至少存在一道正式父题”的知识点。
     *
     * <p>长期规则：Chapter 可练性只取决于该章节是否有正式题，不再看依赖 readiness、
     * 今日是否已经答对或 Review 是否到期。这里只返回 chapter × knowledgePoint 归属，
     * 由 Service 按 chapterId 统计去重后的知识点数量。</p>
     */
    public record ChapterCandidate(String chapterId, String knowledgePointId) {}

    public List<ChapterCandidate> bookChapterCandidates(String learnerId, Collection<String> chapterIds) {
        if (chapterIds == null || chapterIds.isEmpty()) return List.of();
        String marks = String.join(",", java.util.Collections.nCopies(chapterIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(learnerId);                                // 文集归属
        args.addAll(chapterIds);                            // 章节范围
        return jdbc.query("""
                SELECT DISTINCT bk.chapter_id, current_rel.knowledge_point_id
                  FROM question_bank_knowledge bk
                  JOIN learner_selected_book selected ON selected.bank_id = bk.bank_id
                                                     AND selected.learner_id = ?
                  JOIN question_resource_knowledge current_rel
                    ON current_rel.knowledge_point_id = bk.knowledge_point_id
                  JOIN question_resource q ON q.id = current_rel.question_id
                  JOIN global_knowledge_point current_k ON current_k.id = bk.knowledge_point_id
                 WHERE bk.chapter_id IN (%s)
                   AND current_k.status = 'active'
                   AND %s
                   AND %s
                 ORDER BY bk.chapter_id, current_rel.knowledge_point_id
                """.formatted(marks, KnowledgeQuestionCoveragePolicy.anyRelationRole("current_rel"),
                        FormalQuestionPolicy.published("q")),
                (rs, row) -> new ChapterCandidate(rs.getString("chapter_id"),
                        rs.getString("knowledge_point_id")), args.toArray());
    }

    public void setCurrentKnowledgePoint(String id,String learnerId,String pointId){
        jdbc.update("UPDATE learner_practice_session SET current_knowledge_point_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND learner_id=? AND status='active'",pointId,id,learnerId);
    }

    public Optional<Session> latestActiveChapter(String learnerId){
        return sessions("WHERE learner_id=? AND intent='chapter_drill' AND status='active' ORDER BY updated_at DESC LIMIT 1",learnerId).stream().findFirst();
    }

    /** 最近一次章节练习（不论 active 还是 ended），供 Study 页“再次练习”快捷入口使用。 */
    public Optional<Session> latestChapterSession(String learnerId){
        return sessions("WHERE learner_id=? AND intent='chapter_drill' ORDER BY updated_at DESC LIMIT 1",learnerId).stream().findFirst();
    }

    /** 文集与章节的用户可见名称；已下架或已删除时返回空，由 Service 决定降级文案。 */
    public Optional<ChapterNames> chapterNames(String bookId, String chapterId){
        return jdbc.query("""
                SELECT b.name book_name, c.name chapter_name
                  FROM question_bank b JOIN question_bank_chapter c ON c.bank_id = b.id
                 WHERE b.id = ? AND c.id = ?
                """, (rs, row) -> new ChapterNames(rs.getString("book_name"), rs.getString("chapter_name")),
                bookId, chapterId).stream().findFirst();
    }

    public record ChapterNames(String bookName, String chapterName) {}

    /**
     * 当前 Session 实际练到哪道题（用于“最近章节”进度展示）。
     * 只读最近一次 attempt 的题目，不创建新的状态。
     */
    public Optional<String> currentQuestionId(String sessionId){
        return jdbc.query("""
                SELECT question_id FROM study_attempt WHERE practice_session_id=?
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, (rs, row) -> rs.getString(1), sessionId).stream().findFirst();
    }

    /**
     * wrong_drill 的候选：当前 Learner 的 active 错题，限定在该 Session 冻结的
     * KnowledgePoint scope 内，且题目仍然是可练的 published 正式父题。随机由 Service 负责。
     *
     * <p>只按 {@code scopedKnowledgePointIds} 判断范围，不读取实时
     * {@code learner_selected_book}：已经开始的 active Session 不会因为 Learner 在别处
     * 改了学习范围而换题池。错题本原始归因 {@code target_knowledge_point_id} 继续保留，
     * 用于 wrong_review 与错题列表。</p>
     */
    public List<String> wrongDrillQuestionIds(String learnerId, Set<String> scopedKnowledgePointIds,
                                              Collection<String> excludedQuestionIds) {
        if (scopedKnowledgePointIds == null || scopedKnowledgePointIds.isEmpty()) return List.of();
        List<String> points = List.copyOf(scopedKnowledgePointIds);
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.addAll(points);
        StringBuilder exclusion = new StringBuilder();
        if (excludedQuestionIds != null && !excludedQuestionIds.isEmpty()) {
            exclusion.append(" AND wrong.question_id NOT IN (")
                    .append(String.join(",", java.util.Collections.nCopies(excludedQuestionIds.size(), "?")))
                    .append(")");
            args.addAll(excludedQuestionIds);
        }
        return jdbc.query("""
                SELECT wrong.question_id
                  FROM learner_wrong_question wrong
                  JOIN question_resource q ON q.id = wrong.question_id
                  JOIN global_knowledge_point k ON k.id = wrong.target_knowledge_point_id
                 WHERE wrong.learner_id = ? AND wrong.status = 'active'
                   AND wrong.target_knowledge_point_id IN (%s)
                   AND q.status = 'published' AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                   AND k.status = 'active' AND %s
                   %s
                 ORDER BY wrong.question_id
                """.formatted(placeholders(points.size()), TrainableKnowledge.exists("k"), exclusion),
                (rs, row) -> rs.getString(1), args.toArray());
    }

    /** active 错题总数（不要求当前可练），用于 Study 页按钮禁用与友好提示。 */
    public int activeWrongQuestionCount(String learnerId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_wrong_question WHERE learner_id=? AND status='active'",
                Integer.class, learnerId);
        return count == null ? 0 : count;
    }

    public Optional<Session> find(String id, String learnerId) {
        return sessions("WHERE id=? AND learner_id=?", id, learnerId).stream().findFirst();
    }

    public Session lock(String id, String learnerId) {
        return sessions("WHERE id=? AND learner_id=? FOR UPDATE", id, learnerId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("专项练习不存在或不属于当前学习者。"));
    }

    public Set<String> scope(String id) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT knowledge_point_id FROM learner_practice_scope
                 WHERE session_id=? ORDER BY knowledge_point_id
                """, (rs, row) -> rs.getString(1), id));
    }

    /**
     * 本 Session 已经见过的题：既包括直接发卷的 attempt，也包括同一 Session 诊断流程
     * （dependency probe / remediation、target recheck / remediation）发出的题，
     * 否则诊断题会被当成“没见过”而在同一轮里重复出现。
     */
    public Set<String> seenQuestions(String id) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT a.question_id FROM study_attempt a
                 WHERE a.practice_session_id = ?
                    OR a.diagnosis_session_id IN (
                        SELECT diagnosis.id FROM learner_diagnosis_session diagnosis
                         WHERE diagnosis.practice_session_id = ?
                    )
                 ORDER BY a.question_id
                """, (rs, row) -> rs.getString(1), id, id));
    }

    public void setCurrentAttempt(String id, String learnerId, String attemptId) {
        int changed = jdbc.update("""
                UPDATE learner_practice_session
                   SET current_attempt_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND learner_id=? AND status='active'
                """, attemptId, id, learnerId);
        if (changed != 1) throw new IllegalStateException("专项练习状态已经变化。");
    }

    public void end(String id, String learnerId, Instant now) {
        int changed = jdbc.update("""
                UPDATE learner_practice_session
                   SET status='ended',ended_at=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                 WHERE id=? AND learner_id=? AND status='active'
                """, Timestamp.from(now), id, learnerId);
        if (changed != 1) throw new IllegalStateException("专项练习已经结束。");
    }

    public List<WrongQuestion> wrongQuestions(String learnerId) {
        List<WrongQuestion> questions = jdbc.query("""
                SELECT wrong.question_id,wrong.target_knowledge_point_id,k.name knowledge_name,
                       q.content_markdown,q.subject_name,q.exam_year,q.question_number,
                       wrong.last_wrong_at,
                       CASE
                           WHEN NOT (%s) THEN 'out_of_scope'
                           WHEN NOT (q.status='published' AND q.parent_question_id IS NULL
                                     AND q.question_type IN ('single_choice','multiple_choice','true_false','solution'))
                               THEN 'question_unavailable'
                           WHEN NOT (k.status='active' AND %s) THEN 'knowledge_unavailable'
                           ELSE NULL
                       END unavailable_reason
                  FROM learner_wrong_question wrong
                  JOIN question_resource q ON q.id=wrong.question_id
                  JOIN global_knowledge_point k ON k.id=wrong.target_knowledge_point_id
                 WHERE wrong.learner_id=? AND wrong.status='active'
                 ORDER BY wrong.last_wrong_at DESC,wrong.question_id
                """.formatted(inWrongQuestionScope(), TrainableKnowledge.exists("k")),
                (rs, row) -> new WrongQuestion(rs.getString("question_id"),
                rs.getString("target_knowledge_point_id"), rs.getString("knowledge_name"),
                rs.getString("content_markdown"), rs.getString("subject_name"),
                rs.getObject("exam_year", Integer.class), rs.getString("question_number"),
                rs.getTimestamp("last_wrong_at").toInstant(),
                rs.getString("unavailable_reason") == null, rs.getString("unavailable_reason"),
                List.of()), learnerId);
        if (questions.isEmpty()) return questions;
        List<String> questionIds = questions.stream().map(WrongQuestion::questionId).toList();
        Map<String, List<KnowledgePointTag>> tags = new LinkedHashMap<>();
        jdbc.query("""
                SELECT qk.question_id, k.id knowledge_point_id, k.name, qk.relation_role
                  FROM question_resource_knowledge qk
                  JOIN global_knowledge_point k ON k.id = qk.knowledge_point_id
                 WHERE qk.question_id IN (%s) AND k.status = 'active'
                 ORDER BY qk.question_id, qk.sort_order, k.id
                """.formatted(String.join(",", java.util.Collections.nCopies(questionIds.size(), "?"))),
                (RowCallbackHandler) rs -> tags
                        .computeIfAbsent(rs.getString("question_id"), ignored -> new ArrayList<>())
                        .add(new KnowledgePointTag(rs.getString("knowledge_point_id"), rs.getString("name"),
                                rs.getString("relation_role"))), questionIds.toArray());
        return questions.stream().map(question -> question.withKnowledgePoints(
                tags.getOrDefault(question.questionId(), List.of()))).toList();
    }

    /**
     * Login 与 bookScope 的“学习范围”保持一致的 SQL 片段：知识点必须通过现代
     * question_bank_knowledge 或 legacy_knowledge_map 归属于该 Learner 已选且启用的文集。
     * 未选择任何文集时范围视为全部启用文集（与 KnowledgeQuestionPoolStore.enabledBookIds 一致）。
     */
    private static String inWrongQuestionScope() {
        return """
                EXISTS (
                    SELECT 1
                      FROM question_bank scope_book
                     WHERE scope_book.enabled = TRUE
                       AND (
                           NOT EXISTS (
                               SELECT 1 FROM learner_selected_book scope_any WHERE scope_any.learner_id = wrong.learner_id
                           )
                           OR EXISTS (
                               SELECT 1 FROM learner_selected_book scope_selected
                                WHERE scope_selected.learner_id = wrong.learner_id
                                  AND scope_selected.bank_id = scope_book.id
                           )
                       )
                       AND (
                           EXISTS (
                               SELECT 1 FROM question_bank_knowledge scope_modern
                                WHERE scope_modern.bank_id = scope_book.id
                                  AND scope_modern.knowledge_point_id = wrong.target_knowledge_point_id
                           )
                           OR EXISTS (
                               SELECT 1 FROM legacy_knowledge_map scope_legacy
                                WHERE scope_legacy.bank_id = scope_book.id
                                  AND scope_legacy.global_id = wrong.target_knowledge_point_id
                           )
                       )
                )
                """.trim();
    }

    public Optional<WrongQuestion> activeWrongQuestion(String learnerId, String questionId) {
        return wrongQuestions(learnerId).stream().filter(item -> item.questionId().equals(questionId)).findFirst();
    }

    public boolean removeWrongQuestion(String learnerId, String questionId, Instant now) {
        return jdbc.update("""
                UPDATE learner_wrong_question SET status='removed',removed_at=?,updated_at=CURRENT_TIMESTAMP
                 WHERE learner_id=? AND question_id=? AND status='active'
                """, Timestamp.from(now), learnerId, questionId) == 1;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private List<Session> sessions(String predicate, Object... args) {
        return jdbc.query("""
                SELECT id,learner_id,intent,target_knowledge_point_id,target_book_id,target_chapter_id,
                       current_knowledge_point_id,source_question_id,current_attempt_id,
                       status,revision,created_at,updated_at,ended_at
                  FROM learner_practice_session %s
                """.formatted(predicate), (rs, row) -> new Session(rs.getString("id"),
                rs.getString("learner_id"), rs.getString("intent"), rs.getString("target_knowledge_point_id"),
                rs.getString("target_book_id"),rs.getString("target_chapter_id"),rs.getString("current_knowledge_point_id"),
                rs.getString("source_question_id"), rs.getString("current_attempt_id"), rs.getString("status"),
                rs.getLong("revision"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant()), args);
    }

}

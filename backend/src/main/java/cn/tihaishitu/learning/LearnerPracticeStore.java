package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class LearnerPracticeStore {
    public record Session(String id, String learnerId, String intent, String targetKnowledgePointId,
                          String targetBookId, String targetChapterId, String currentKnowledgePointId,
                          String sourceQuestionId, String currentAttemptId, String status, long revision,
                          Instant createdAt, Instant updatedAt, Instant endedAt) {}
    public record WrongQuestion(String questionId, String targetKnowledgePointId, String knowledgePointName,
                                String contentMarkdown, Instant lastGradedAt, boolean available,
                                String unavailableReason) {}

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
     * 章节内仍可能出题的候选知识点及其复习排期数据。是否真正“可练”由
     * LearnerPracticeService 用同一条 ReviewSchedulingPolicy 判定，避免在 SQL 里
     * 使用 MySQL 5.7 不支持的日期函数。
     * 语义与 chapter_drill 入口校验一致：已发布父真题、依赖满足、且该题对 Learner
     * 而言仍是未见 / 最近一次答错或部分正确 / 上一个业务日答对 / 知识点复习到期。
     */
    public record ChapterCandidate(String knowledgePointId, Double masteryScore, Double stabilityDays,
                                   Instant lastEvidenceAt, boolean readyQuestion) {}

    public List<ChapterCandidate> chapterCandidates(String learnerId, String bookId, String chapterId,
                                                    java.sql.Date businessDate) {
        return jdbc.query("""
                SELECT candidate.knowledge_point_id,
                       MAX(CASE
                           WHEN NOT EXISTS (
                               SELECT 1 FROM study_attempt exposure
                                WHERE exposure.learner_id = ? AND exposure.question_id = q.id
                                  AND NOT EXISTS (
                                      SELECT 1 FROM learner_diagnosis_session diagnosis
                                       WHERE diagnosis.root_attempt_id = exposure.id
                                         AND NOT EXISTS (
                                             SELECT 1 FROM learner_knowledge_evidence evidence
                                              WHERE evidence.attempt_id = exposure.id
                                         )
                                  )
                           ) THEN TRUE
                           WHEN EXISTS (
                               SELECT 1 FROM study_attempt latest
                                WHERE latest.learner_id = ? AND latest.question_id = q.id
                                  AND latest.target_knowledge_point_id = candidate.knowledge_point_id
                                  AND latest.status = 'graded'
                                  AND NOT EXISTS (
                                      SELECT 1 FROM learner_diagnosis_session diagnosis
                                       WHERE diagnosis.root_attempt_id = latest.id
                                         AND NOT EXISTS (
                                             SELECT 1 FROM learner_knowledge_evidence evidence
                                              WHERE evidence.attempt_id = latest.id
                                         )
                                  )
                                  AND NOT EXISTS (
                                      SELECT 1 FROM study_attempt newer
                                       WHERE newer.learner_id = latest.learner_id
                                         AND newer.target_knowledge_point_id = latest.target_knowledge_point_id
                                         AND newer.question_id = latest.question_id
                                         AND newer.status = 'graded'
                                         AND NOT EXISTS (
                                             SELECT 1 FROM learner_diagnosis_session diagnosis
                                              WHERE diagnosis.root_attempt_id = newer.id
                                                AND NOT EXISTS (
                                                    SELECT 1 FROM learner_knowledge_evidence evidence
                                                     WHERE evidence.attempt_id = newer.id
                                                )
                                         )
                                         AND (newer.answered_at > latest.answered_at
                                              OR (newer.answered_at = latest.answered_at AND newer.id > latest.id))
                                  )
                                  AND (latest.assessment IN ('wrong','partial')
                                       OR CAST(latest.answered_at AS DATE) < ?)
                           ) THEN TRUE
                           ELSE FALSE
                       END) ready_question,
                       state.mastery_score, state.stability_days, state.last_evidence_at
                  FROM question_resource_knowledge candidate
                  JOIN question_resource q ON q.id = candidate.question_id
                  JOIN global_knowledge_point current_k ON current_k.id = candidate.knowledge_point_id
                  LEFT JOIN learner_knowledge_state state ON state.learner_id = ?
                                                        AND state.knowledge_point_id = candidate.knowledge_point_id
                 WHERE candidate.relation_role = 'core'
                   AND q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                   AND current_k.status = 'active'
                   AND candidate.knowledge_point_id IN (
                       SELECT bk.knowledge_point_id FROM question_bank_knowledge bk
                        WHERE bk.bank_id = ? AND bk.chapter_id = ?
                   )
                   AND NOT EXISTS (
                       SELECT 1 FROM question_resource_knowledge dependency
                         LEFT JOIN global_knowledge_point dependency_k ON dependency_k.id = dependency.knowledge_point_id
                        WHERE dependency.question_id = q.id
                          AND (dependency_k.id IS NULL OR dependency_k.status <> 'active'
                               OR NOT EXISTS (
                                   SELECT 1 FROM question_bank_knowledge allowed
                                    JOIN learner_selected_book selected ON selected.bank_id = allowed.bank_id
                                   WHERE selected.learner_id = ?
                                     AND allowed.knowledge_point_id = dependency.knowledge_point_id
                               ))
                   )
                 GROUP BY candidate.knowledge_point_id, state.mastery_score, state.stability_days,
                          state.last_evidence_at
                """, (rs, row) -> new ChapterCandidate(rs.getString("knowledge_point_id"),
                number(rs.getObject("mastery_score")), number(rs.getObject("stability_days")),
                rs.getTimestamp("last_evidence_at") == null ? null : rs.getTimestamp("last_evidence_at").toInstant(),
                rs.getBoolean("ready_question")), learnerId, learnerId, businessDate, learnerId,
                bookId, chapterId, learnerId);
    }

    /** DECIMAL 列在 H2/MySQL 上可能返回 BigDecimal、Double 或 Float，统一按 Number 取值。 */
    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    public void setCurrentKnowledgePoint(String id,String learnerId,String pointId){
        jdbc.update("UPDATE learner_practice_session SET current_knowledge_point_id=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND learner_id=? AND status='active'",pointId,id,learnerId);
    }

    public Optional<Session> latestActiveChapter(String learnerId){
        return sessions("WHERE learner_id=? AND intent='chapter_drill' AND status='active' ORDER BY updated_at DESC LIMIT 1",learnerId).stream().findFirst();
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

    public Set<String> seenQuestions(String id) {
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT question_id FROM study_attempt
                 WHERE practice_session_id=? ORDER BY question_id
                """, (rs, row) -> rs.getString(1), id));
    }

    public int attemptCount(String sessionId,String questionId){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM study_attempt WHERE practice_session_id=? AND question_id=?",Integer.class,sessionId,questionId);
        return count==null?0:count;
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
        return jdbc.query("""
                SELECT wrong.question_id,wrong.target_knowledge_point_id,k.name knowledge_name,
                       q.content_markdown,wrong.last_wrong_at,
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
                rs.getString("content_markdown"), rs.getTimestamp("last_wrong_at").toInstant(),
                rs.getString("unavailable_reason") == null, rs.getString("unavailable_reason")), learnerId);
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

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
                                String contentMarkdown, Instant lastGradedAt, boolean available) {}

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
                       CASE WHEN q.status='published' AND q.parent_question_id IS NULL
                                  AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                                  AND k.status='active' AND %s THEN TRUE ELSE FALSE END available
                  FROM learner_wrong_question wrong
                  JOIN question_resource q ON q.id=wrong.question_id
                  JOIN global_knowledge_point k ON k.id=wrong.target_knowledge_point_id
                 WHERE wrong.learner_id=? AND wrong.status='active'
                 ORDER BY wrong.last_wrong_at DESC,wrong.question_id
                """.formatted(TrainableKnowledge.exists("k")), (rs, row) -> new WrongQuestion(rs.getString("question_id"),
                rs.getString("target_knowledge_point_id"), rs.getString("knowledge_name"),
                rs.getString("content_markdown"), rs.getTimestamp("last_wrong_at").toInstant(),
                rs.getBoolean("available")), learnerId);
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

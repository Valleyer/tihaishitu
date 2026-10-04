package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class LearnerKnowledgeStateStore {
    public record EvidenceRow(String id, String learnerId, String knowledgePointId, String attemptId,
                              String questionId, String worldId, String outcome, String gradingSource,
                              String evidenceMode, int questionDifficulty, Instant occurredAt) {}

    private final JdbcTemplate jdbc;
    public LearnerKnowledgeStateStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<KnowledgeMasteryModel.State> find(String learnerId, String pointId) {
        return jdbc.query("""
                SELECT mastery_score, stability_days, target_difficulty, evidence_count, correct_streak,
                       wrong_streak, last_outcome, last_evidence_at, last_correct_at, model_version, revision
                  FROM learner_knowledge_state WHERE learner_id = ? AND knowledge_point_id = ?
                """, (rs, row) -> state(rs), learnerId, pointId).stream().findFirst();
    }

    public List<StateRow> findForKnowledgePoints(String learnerId, Collection<String> pointIds) {
        if (pointIds.isEmpty()) return List.of();
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.addAll(pointIds);
        return jdbc.query("""
                SELECT knowledge_point_id, mastery_score, stability_days, target_difficulty,
                       evidence_count, correct_streak, wrong_streak, last_outcome, last_evidence_at,
                       last_correct_at, model_version, revision
                  FROM learner_knowledge_state
                 WHERE learner_id = ? AND knowledge_point_id IN (%s)
                """.formatted(placeholders(pointIds.size())), (rs, row) ->
                new StateRow(rs.getString("knowledge_point_id"), state(rs)), args.toArray());
    }

    public List<StateRow> findForBook(String learnerId, String bookId) {
        return jdbc.query("""
                SELECT bk.knowledge_point_id,
                       s.mastery_score, s.stability_days, s.target_difficulty, s.evidence_count,
                       s.correct_streak, s.wrong_streak, s.last_outcome, s.last_evidence_at,
                       s.last_correct_at, s.model_version, s.revision
                  FROM question_bank_knowledge bk
                  JOIN question_bank b ON b.id = bk.bank_id AND b.enabled = TRUE
                  JOIN global_knowledge_point k ON k.id = bk.knowledge_point_id AND k.status = 'active'
                  LEFT JOIN learner_knowledge_state s
                    ON s.knowledge_point_id = bk.knowledge_point_id AND s.learner_id = ?
                 WHERE bk.bank_id = ?
                 ORDER BY bk.sort_order, bk.knowledge_point_id
                """, (rs, row) -> new StateRow(rs.getString("knowledge_point_id"),
                rs.getObject("mastery_score") == null ? null : state(rs)), learnerId, bookId);
    }

    public boolean enabledBookExists(String bookId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank WHERE id=? AND enabled=TRUE",
                Integer.class, bookId);
        return count != null && count > 0;
    }

    public boolean activeKnowledgeExists(String pointId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM global_knowledge_point WHERE id=? AND status='active'",
                Integer.class, pointId);
        return count != null && count > 0;
    }

    public boolean evidenceExists(String attemptId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id = ?",
                Integer.class, attemptId);
        return count != null && count > 0;
    }

    public void save(String learnerId, String pointId, KnowledgeMasteryModel.State state) {
        int changed = jdbc.update("""
                UPDATE learner_knowledge_state
                   SET mastery_score=?, stability_days=?, target_difficulty=?, evidence_count=?,
                       correct_streak=?, wrong_streak=?, last_outcome=?, last_evidence_at=?, last_correct_at=?,
                       model_version=?, revision=?, updated_at=CURRENT_TIMESTAMP
                 WHERE learner_id=? AND knowledge_point_id=?
                """, state.masteryScore(), state.stabilityDays(), state.targetDifficulty(), state.evidenceCount(),
                state.correctStreak(), state.wrongStreak(), state.lastOutcome(), timestamp(state.lastEvidenceAt()),
                timestamp(state.lastCorrectAt()), state.modelVersion(), state.revision(), learnerId, pointId);
        if (changed == 0) jdbc.update("""
                INSERT INTO learner_knowledge_state(
                    learner_id, knowledge_point_id, mastery_score, stability_days, target_difficulty,
                    evidence_count, correct_streak, wrong_streak, last_outcome, last_evidence_at,
                    last_correct_at, model_version, revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, learnerId, pointId, state.masteryScore(), state.stabilityDays(), state.targetDifficulty(),
                state.evidenceCount(), state.correctStreak(), state.wrongStreak(), state.lastOutcome(),
                timestamp(state.lastEvidenceAt()), timestamp(state.lastCorrectAt()), state.modelVersion(), state.revision());
    }

    public void insertEvidence(String id, String learnerId, String pointId, String attemptId, String questionId,
                               String worldId, KnowledgeMasteryModel.Evidence evidence,
                               KnowledgeMasteryModel.Calculation calculation) {
        var next = calculation.next();
        jdbc.update("""
                INSERT INTO learner_knowledge_evidence(
                    id, learner_id, knowledge_point_id, attempt_id, question_id, world_id, outcome,
                    grading_source, evidence_mode, question_difficulty, quality, learning_rate,
                    effective_mastery_before, mastery_after, stability_before, stability_after,
                    target_difficulty_before, target_difficulty_after, model_version, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, learnerId, pointId, attemptId, questionId, worldId, evidence.outcome(),
                evidence.gradingSource(), evidence.evidenceMode(), evidence.questionDifficulty(),
                calculation.quality(), calculation.learningRate(), calculation.effectiveMasteryBefore(),
                next.masteryScore(), calculation.stabilityBefore(), next.stabilityDays(),
                calculation.targetDifficultyBefore(), next.targetDifficulty(), next.modelVersion(),
                timestamp(evidence.occurredAt()));
    }

    public List<String> affectedLearners(String sourceId, String targetId) {
        return jdbc.query("""
                SELECT learner_id FROM learner_knowledge_evidence WHERE knowledge_point_id IN (?, ?)
                UNION
                SELECT learner_id FROM learner_knowledge_state WHERE knowledge_point_id IN (?, ?)
                UNION
                SELECT learner_id FROM learner_focus_knowledge WHERE knowledge_point_id IN (?, ?)
                UNION
                SELECT learner_id FROM study_attempt
                 WHERE learner_id IS NOT NULL AND target_knowledge_point_id IN (?, ?)
                ORDER BY learner_id
                """, (rs, row) -> rs.getString(1), sourceId, targetId, sourceId, targetId,
                sourceId, targetId, sourceId, targetId);
    }

    public void canonicalizeForMerge(String sourceId, String targetId) {
        List<FocusRow> focuses = jdbc.query("""
                SELECT learner_id, sort_order FROM learner_focus_knowledge WHERE knowledge_point_id = ?
                """, (rs, row) -> new FocusRow(rs.getString(1), rs.getInt(2)), sourceId);
        for (FocusRow focus : focuses) {
            Integer targetOrder = jdbc.queryForObject("""
                    SELECT MIN(sort_order) FROM learner_focus_knowledge
                     WHERE learner_id = ? AND knowledge_point_id = ?
                    """, Integer.class, focus.learnerId(), targetId);
            if (targetOrder == null) {
                jdbc.update("UPDATE learner_focus_knowledge SET knowledge_point_id = ? WHERE learner_id = ? AND knowledge_point_id = ?",
                        targetId, focus.learnerId(), sourceId);
            } else {
                jdbc.update("UPDATE learner_focus_knowledge SET sort_order = ? WHERE learner_id = ? AND knowledge_point_id = ?",
                        Math.min(targetOrder, focus.sortOrder()), focus.learnerId(), targetId);
                jdbc.update("DELETE FROM learner_focus_knowledge WHERE learner_id = ? AND knowledge_point_id = ?",
                        focus.learnerId(), sourceId);
            }
            int profileChanged = jdbc.update("""
                    UPDATE learner_study_profile
                       SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE learner_id = ?
                    """, focus.learnerId());
            if (profileChanged != 1) throw new IllegalStateException("重点知识点所属学习档案不存在。");
        }
        jdbc.update("UPDATE learner_knowledge_evidence SET knowledge_point_id = ? WHERE knowledge_point_id = ?", targetId, sourceId);
        jdbc.update("UPDATE study_attempt SET target_knowledge_point_id = ? WHERE target_knowledge_point_id = ?", targetId, sourceId);
        jdbc.update("DELETE FROM learner_knowledge_state WHERE knowledge_point_id = ?", sourceId);
    }

    public List<EvidenceRow> evidenceForReplay(String learnerId, String pointId) {
        return jdbc.query("""
                SELECT id, learner_id, knowledge_point_id, attempt_id, question_id, world_id, outcome,
                       grading_source, evidence_mode, question_difficulty, occurred_at
                  FROM learner_knowledge_evidence
                 WHERE learner_id = ? AND knowledge_point_id = ? ORDER BY occurred_at, id
                """, (rs, row) -> new EvidenceRow(rs.getString("id"), rs.getString("learner_id"),
                rs.getString("knowledge_point_id"), rs.getString("attempt_id"), rs.getString("question_id"),
                rs.getString("world_id"), rs.getString("outcome"), rs.getString("grading_source"),
                rs.getString("evidence_mode"), rs.getInt("question_difficulty"),
                rs.getTimestamp("occurred_at").toInstant()), learnerId, pointId);
    }

    public void updateReplayCalculation(String evidenceId, KnowledgeMasteryModel.Calculation calculation) {
        jdbc.update("""
                UPDATE learner_knowledge_evidence
                   SET quality=?, learning_rate=?, effective_mastery_before=?, mastery_after=?,
                       stability_before=?, stability_after=?, target_difficulty_before=?,
                       target_difficulty_after=?, model_version=? WHERE id=?
                """, calculation.quality(), calculation.learningRate(), calculation.effectiveMasteryBefore(),
                calculation.next().masteryScore(), calculation.stabilityBefore(), calculation.next().stabilityDays(),
                calculation.targetDifficultyBefore(), calculation.next().targetDifficulty(),
                calculation.next().modelVersion(), evidenceId);
    }

    public void deleteState(String learnerId, String pointId) {
        jdbc.update("DELETE FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?", learnerId, pointId);
    }

    private KnowledgeMasteryModel.State state(ResultSet rs) throws SQLException {
        return new KnowledgeMasteryModel.State(rs.getDouble("mastery_score"), rs.getDouble("stability_days"),
                rs.getInt("target_difficulty"), rs.getInt("evidence_count"), rs.getInt("correct_streak"),
                rs.getInt("wrong_streak"), rs.getString("last_outcome"), instant(rs, "last_evidence_at"),
                instant(rs, "last_correct_at"), rs.getString("model_version"), rs.getLong("revision"));
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
    public record StateRow(String knowledgePointId, KnowledgeMasteryModel.State state) {}
    private record FocusRow(String learnerId, int sortOrder) {}
}

package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class LearnerQuestionMasteryStore {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public record Slot(String questionId, double score, Instant firstCorrectAt, Instant lastCorrectAt,
                       LocalDate lastRewardDate, LocalDate lastDecayDate, String lastAssessment,
                       Instant lastAttemptAt, boolean decayFrozen, long revision) {}
    public record Projection(double masteryScore, int evidenceCount, String lastAssessment,
                             Instant lastAttemptAt, Instant lastCorrectAt, boolean frozen) {}
    public record ApplyResult(Projection projection, boolean effective) {}

    private final JdbcTemplate jdbc;
    public LearnerQuestionMasteryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isFormalQuestion(String questionId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM question_resource q WHERE q.id=? AND "
                + FormalQuestionPolicy.published("q"), Integer.class, questionId);
        return count != null && count > 0;
    }

    public ApplyResult apply(String learnerId, String pointId, String questionId,
                             String assessment, Instant occurredAt) {
        settle(learnerId, pointId, occurredAt);
        LocalDate businessDate = occurredAt.atZone(BUSINESS_ZONE).toLocalDate();
        Slot previous = find(learnerId, pointId, questionId);
        double score = previous == null ? 0 : previous.score();
        Instant firstCorrect = previous == null ? null : previous.firstCorrectAt();
        Instant lastCorrect = previous == null ? null : previous.lastCorrectAt();
        LocalDate lastReward = previous == null ? null : previous.lastRewardDate();
        boolean rewarded = false;
        if ("correct".equals(assessment)) {
            if (firstCorrect == null) {
                score = 30;
                firstCorrect = occurredAt;
                rewarded = true;
            } else if (!businessDate.equals(lastReward)) {
                score = Math.min(100, score + 7);
                rewarded = true;
            }
            lastCorrect = occurredAt;
            lastReward = businessDate;
        }
        boolean assessmentChanged = previous == null || !java.util.Objects.equals(previous.lastAssessment(), assessment);
        LocalDate decayDate = rewarded ? businessDate : previous == null ? businessDate : previous.lastDecayDate();
        upsert(learnerId, pointId, questionId, score, firstCorrect, lastCorrect, lastReward,
                decayDate,
                assessment, occurredAt, false, previous == null ? 1 : previous.revision() + 1);
        Projection projection = settle(learnerId, pointId, occurredAt);
        return new ApplyResult(projection, rewarded || assessmentChanged);
    }

    public Projection projection(String learnerId, String pointId, Instant now) {
        return settle(learnerId, pointId, now);
    }

    public void rebuild(String learnerId, String pointId, Instant now) {
        jdbc.update("DELETE FROM learner_question_mastery WHERE learner_id=? AND knowledge_point_id=?",
                learnerId, pointId);
        record History(String questionId, String assessment, Instant answeredAt) {}
        List<History> rows = jdbc.query("""
                SELECT a.question_id,a.assessment,a.answered_at
                  FROM study_attempt a
                  JOIN question_resource q ON q.id=a.question_id
                  JOIN question_resource_knowledge qk ON qk.question_id=q.id
                 WHERE a.learner_id=? AND a.target_knowledge_point_id=? AND a.status='graded'
                   AND qk.knowledge_point_id=? AND qk.relation_role='core' AND %s
                 ORDER BY a.answered_at,a.id
                """.formatted(FormalQuestionPolicy.published("q")), (rs, row) ->
                new History(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
                learnerId, pointId, pointId);
        Map<String, List<History>> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.questionId(), ignored -> new ArrayList<>()).add(row));
        grouped.forEach((questionId, history) -> {
            List<History> correct = history.stream().filter(item -> "correct".equals(item.assessment())).toList();
            double score = 0; Instant first = null, lastCorrect = null; LocalDate rewardDate = null;
            if (!correct.isEmpty()) {
                first = correct.get(0).answeredAt(); lastCorrect = correct.get(correct.size() - 1).answeredAt();
                long dates = correct.stream().map(item -> item.answeredAt().atZone(BUSINESS_ZONE).toLocalDate())
                        .distinct().count();
                score = Math.min(100, 30 + Math.max(0, dates - 1) * 7);
                rewardDate = lastCorrect.atZone(BUSINESS_ZONE).toLocalDate();
            }
            History last = history.get(history.size() - 1);
            LocalDate decay = rewardDate == null ? last.answeredAt().atZone(BUSINESS_ZONE).toLocalDate() : rewardDate;
            upsert(learnerId, pointId, questionId, score, first, lastCorrect, rewardDate, decay,
                    last.assessment(), last.answeredAt(), false, 1);
        });
        settle(learnerId, pointId, now);
    }

    public void deleteForPoint(String pointId) {
        jdbc.update("DELETE FROM learner_question_mastery WHERE knowledge_point_id=?", pointId);
    }

    private Projection settle(String learnerId, String pointId, Instant now) {
        LocalDate today = now.atZone(BUSINESS_ZONE).toLocalDate();
        int formalCount = formalQuestionCount(pointId);
        List<Slot> slots = slots(learnerId, pointId);
        long perfect = slots.stream().filter(slot -> slot.score() >= 100).count();
        boolean fullyMastered = formalCount > 0 && perfect == formalCount;
        for (Slot slot : slots) {
            if (fullyMastered) {
                if (!slot.decayFrozen()) setFrozen(learnerId, pointId, slot.questionId(), true, slot.lastDecayDate());
                continue;
            }
            if (slot.decayFrozen()) {
                setFrozen(learnerId, pointId, slot.questionId(), false, today);
                continue;
            }
            LocalDate anchor = slot.lastDecayDate();
            if (anchor == null) anchor = slot.lastCorrectAt() == null ? today
                    : slot.lastCorrectAt().atZone(BUSINESS_ZONE).toLocalDate();
            long steps = Math.max(0, ChronoUnit.DAYS.between(anchor, today) / 3);
            if (steps > 0 && slot.score() > 0) {
                double score = Math.max(0, slot.score() - steps);
                LocalDate settled = anchor.plusDays(steps * 3);
                jdbc.update("""
                        UPDATE learner_question_mastery SET score=?,last_decay_date=?,revision=revision+1,
                               updated_at=CURRENT_TIMESTAMP WHERE learner_id=? AND knowledge_point_id=? AND question_id=?
                        """, score, Date.valueOf(settled), learnerId, pointId, slot.questionId());
            }
        }
        slots = slots(learnerId, pointId);
        double total = slots.stream().mapToDouble(Slot::score).sum();
        Slot latest = slots.stream().filter(slot -> slot.lastAttemptAt() != null)
                .max(java.util.Comparator.comparing(Slot::lastAttemptAt)).orElse(null);
        Instant lastCorrect = slots.stream().map(Slot::lastCorrectAt).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        double mastery = formalCount == 0 ? 0 : Math.round(total / formalCount * 10d) / 10d;
        boolean frozen = formalCount > 0 && slots.stream().filter(slot -> slot.score() >= 100).count() == formalCount;
        return new Projection(mastery, slots.size(), latest == null ? null : latest.lastAssessment(),
                latest == null ? null : latest.lastAttemptAt(), lastCorrect, frozen);
    }

    private int formalQuestionCount(String pointId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT q.id) FROM question_resource q
                JOIN question_resource_knowledge qk ON qk.question_id=q.id
                WHERE qk.knowledge_point_id=? AND qk.relation_role='core' AND %s
                """.formatted(FormalQuestionPolicy.published("q")), Integer.class, pointId);
        return count == null ? 0 : count;
    }

    private Slot find(String learnerId, String pointId, String questionId) {
        return jdbc.query("""
                SELECT question_id,score,first_correct_at,last_correct_at,last_reward_date,last_decay_date,
                       last_assessment,last_attempt_at,decay_frozen,revision
                  FROM learner_question_mastery
                 WHERE learner_id=? AND knowledge_point_id=? AND question_id=?
                """, (rs, row) -> slot(rs), learnerId, pointId, questionId).stream().findFirst().orElse(null);
    }

    private List<Slot> slots(String learnerId, String pointId) {
        return jdbc.query("""
                SELECT m.question_id,m.score,m.first_correct_at,m.last_correct_at,m.last_reward_date,m.last_decay_date,
                       m.last_assessment,m.last_attempt_at,m.decay_frozen,m.revision
                  FROM learner_question_mastery m
                  JOIN question_resource q ON q.id=m.question_id
                  JOIN question_resource_knowledge qk ON qk.question_id=q.id
                 WHERE m.learner_id=? AND m.knowledge_point_id=?
                   AND qk.knowledge_point_id=? AND qk.relation_role='core' AND %s
                """.formatted(FormalQuestionPolicy.published("q")), (rs, row) -> slot(rs),
                learnerId, pointId, pointId);
    }

    private void upsert(String learnerId, String pointId, String questionId, double score,
                        Instant firstCorrect, Instant lastCorrect, LocalDate rewardDate, LocalDate decayDate,
                        String assessment, Instant attemptAt, boolean frozen, long revision) {
        int changed = jdbc.update("""
                UPDATE learner_question_mastery SET score=?,first_correct_at=?,last_correct_at=?,last_reward_date=?,
                       last_decay_date=?,last_assessment=?,last_attempt_at=?,decay_frozen=?,revision=?,updated_at=CURRENT_TIMESTAMP
                 WHERE learner_id=? AND knowledge_point_id=? AND question_id=?
                """, score, timestamp(firstCorrect), timestamp(lastCorrect), date(rewardDate), date(decayDate), assessment,
                timestamp(attemptAt), frozen, revision, learnerId, pointId, questionId);
        if (changed == 0) jdbc.update("""
                INSERT INTO learner_question_mastery(learner_id,knowledge_point_id,question_id,score,
                    first_correct_at,last_correct_at,last_reward_date,last_decay_date,last_assessment,last_attempt_at,
                    decay_frozen,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, learnerId, pointId, questionId, score, timestamp(firstCorrect), timestamp(lastCorrect),
                date(rewardDate), date(decayDate), assessment, timestamp(attemptAt), frozen, revision);
    }

    private void setFrozen(String learnerId, String pointId, String questionId, boolean frozen, LocalDate date) {
        jdbc.update("""
                UPDATE learner_question_mastery SET decay_frozen=?,last_decay_date=?,revision=revision+1,
                       updated_at=CURRENT_TIMESTAMP WHERE learner_id=? AND knowledge_point_id=? AND question_id=?
                """, frozen, date(date), learnerId, pointId, questionId);
    }

    private static Slot slot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Slot(rs.getString("question_id"), rs.getDouble("score"), instant(rs.getTimestamp("first_correct_at")),
                instant(rs.getTimestamp("last_correct_at")), localDate(rs.getDate("last_reward_date")),
                localDate(rs.getDate("last_decay_date")), rs.getString("last_assessment"),
                instant(rs.getTimestamp("last_attempt_at")), rs.getBoolean("decay_frozen"), rs.getLong("revision"));
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Date date(LocalDate value) { return value == null ? null : Date.valueOf(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static LocalDate localDate(Date value) { return value == null ? null : value.toLocalDate(); }
}

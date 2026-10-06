package cn.tihaishitu.learning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class LearnerQuestionProgressStore {
    public record QuestionProgress(String questionId, String targetKnowledgePointId, String assessment,
                                   Instant answeredAt, String attemptId, JsonNode questionSnapshot,
                                   JsonNode standardAnswer, int exposureCount, Instant lastExposedAt) {
        public boolean graded() { return assessment != null; }
    }

    public record Coverage(int publishedCoreQuestions, int seenQuestions, double contribution,
                           String lastOutcome, Instant lastEvidenceAt, Instant lastCorrectAt) {
        public double masteryScore() {
            return publishedCoreQuestions == 0 ? 0
                    : KnowledgeModelPolicy.round(100d * contribution / publishedCoreQuestions);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LearnerQuestionProgressStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, QuestionProgress> latestGradedForQuestions(
            String learnerId, String targetKnowledgePointId, Collection<String> questionIds) {
        if (learnerId == null || learnerId.isBlank() || questionIds.isEmpty()) return Map.of();
        Map<String, Exposure> exposures = exposures(learnerId, questionIds);
        Map<String, QuestionProgress> result = new LinkedHashMap<>();
        exposures.forEach((id, exposure) -> result.put(id, new QuestionProgress(
                id, targetKnowledgePointId, null, null, null, null, null,
                exposure.count(), exposure.lastExposedAt())));
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.add(targetKnowledgePointId);
        args.addAll(questionIds);
        jdbc.query("""
                SELECT a.question_id,a.target_knowledge_point_id,a.assessment,a.answered_at,a.id,
                       a.question_snapshot_json,a.standard_answer_json
                  FROM study_attempt a
                 WHERE a.learner_id=? AND a.target_knowledge_point_id=? AND a.status='graded'
                   AND a.question_id IN (%s)
                   AND NOT EXISTS (
                       SELECT 1 FROM learner_diagnosis_session diagnosis
                        WHERE diagnosis.root_attempt_id=a.id
                          AND NOT EXISTS (SELECT 1 FROM learner_knowledge_evidence evidence
                                           WHERE evidence.attempt_id=a.id))
                   AND NOT EXISTS (
                       SELECT 1 FROM study_attempt newer
                        WHERE newer.learner_id=a.learner_id
                          AND newer.target_knowledge_point_id=a.target_knowledge_point_id
                          AND newer.question_id=a.question_id AND newer.status='graded'
                          AND NOT EXISTS (
                              SELECT 1 FROM learner_diagnosis_session diagnosis
                               WHERE diagnosis.root_attempt_id=newer.id
                                 AND NOT EXISTS (SELECT 1 FROM learner_knowledge_evidence evidence
                                                  WHERE evidence.attempt_id=newer.id))
                          AND (newer.answered_at>a.answered_at
                               OR (newer.answered_at=a.answered_at AND newer.id>a.id)))
                """.formatted(placeholders(questionIds.size())), row -> {
            String questionId = row.getString("question_id");
            Exposure exposure = exposures.getOrDefault(questionId, new Exposure(0, null));
            result.put(questionId, new QuestionProgress(questionId,
                    row.getString("target_knowledge_point_id"), row.getString("assessment"),
                    instant(row.getTimestamp("answered_at")), row.getString("id"),
                    json(row.getString("question_snapshot_json")), json(row.getString("standard_answer_json")),
                    exposure.count(), exposure.lastExposedAt()));
        }, args.toArray());
        return Map.copyOf(result);
    }

    public Optional<QuestionProgress> latestAttemptForQuestion(String learnerId, String questionId) {
        return jdbc.query("""
                SELECT question_id,target_knowledge_point_id,assessment,answered_at,id,
                       question_snapshot_json,standard_answer_json,created_at
                  FROM study_attempt WHERE learner_id=? AND question_id=?
                 ORDER BY created_at DESC,id DESC LIMIT 1
                """, (row, index) -> new QuestionProgress(row.getString("question_id"),
                row.getString("target_knowledge_point_id"), row.getString("assessment"),
                instant(row.getTimestamp("answered_at")), row.getString("id"),
                json(row.getString("question_snapshot_json")), json(row.getString("standard_answer_json")),
                0, instant(row.getTimestamp("created_at"))), learnerId, questionId).stream().findFirst();
    }

    public Coverage coverage(String learnerId, String knowledgePointId) {
        return coverage(learnerId, knowledgePointId, null, null);
    }

    public Coverage coverage(String learnerId, String knowledgePointId, String excludedAttemptId) {
        return coverage(learnerId, knowledgePointId, excludedAttemptId, null);
    }

    public Coverage coverageIncludingAttempt(String learnerId, String knowledgePointId, String includedAttemptId) {
        return coverage(learnerId, knowledgePointId, null, includedAttemptId);
    }

    private Coverage coverage(String learnerId, String knowledgePointId, String excludedAttemptId,
                              String includedAttemptId) {
        List<String> questionIds = jdbc.query("""
                SELECT DISTINCT q.id FROM question_resource q
                JOIN question_resource_knowledge qk ON qk.question_id=q.id
                WHERE qk.knowledge_point_id=? AND qk.relation_role='core'
                  AND q.status='published'
                  AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                """, (row, index) -> row.getString(1), knowledgePointId);
        if (questionIds.isEmpty()) return new Coverage(0, 0, 0, null, null, null);
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.add(knowledgePointId);
        args.addAll(questionIds);
        args.add(excludedAttemptId == null ? "" : excludedAttemptId);
        args.add(includedAttemptId == null ? "" : includedAttemptId);
        args.add(excludedAttemptId == null ? "" : excludedAttemptId);
        args.add(includedAttemptId == null ? "" : includedAttemptId);
        List<Latest> latest = jdbc.query("""
                SELECT a.assessment,a.answered_at,a.id
                  FROM study_attempt a
                 WHERE a.learner_id=? AND a.target_knowledge_point_id=? AND a.status='graded'
                   AND a.question_id IN (%s) AND a.id<>?
                   AND (a.id=? OR NOT EXISTS (
                       SELECT 1 FROM learner_diagnosis_session diagnosis
                        WHERE diagnosis.root_attempt_id=a.id
                          AND NOT EXISTS (SELECT 1 FROM learner_knowledge_evidence evidence
                                           WHERE evidence.attempt_id=a.id)))
                   AND NOT EXISTS (
                       SELECT 1 FROM study_attempt newer
                        WHERE newer.learner_id=a.learner_id
                          AND newer.target_knowledge_point_id=a.target_knowledge_point_id
                          AND newer.question_id=a.question_id AND newer.status='graded' AND newer.id<>?
                          AND (newer.id=? OR NOT EXISTS (
                              SELECT 1 FROM learner_diagnosis_session diagnosis
                               WHERE diagnosis.root_attempt_id=newer.id
                                 AND NOT EXISTS (SELECT 1 FROM learner_knowledge_evidence evidence
                                                  WHERE evidence.attempt_id=newer.id)))
                          AND (newer.answered_at>a.answered_at
                               OR (newer.answered_at=a.answered_at AND newer.id>a.id)))
                 ORDER BY a.answered_at DESC,a.id DESC
                """.formatted(placeholders(questionIds.size())), (row, index) ->
                new Latest(row.getString("assessment"), instant(row.getTimestamp("answered_at"))), args.toArray());
        double contribution = latest.stream().mapToDouble(item -> contribution(item.assessment())).sum();
        Instant lastCorrect = latest.stream().filter(item -> "correct".equals(item.assessment()))
                .map(Latest::answeredAt).max(Instant::compareTo).orElse(null);
        Latest last = latest.isEmpty() ? null : latest.get(0);
        return new Coverage(questionIds.size(), latest.size(), contribution,
                last == null ? null : last.assessment(), last == null ? null : last.answeredAt(), lastCorrect);
    }

    public String latestAssessmentBeforeAttempt(String learnerId, String knowledgePointId,
                                                String questionId, String excludedAttemptId) {
        return jdbc.query("""
                SELECT assessment FROM study_attempt
                 WHERE learner_id=? AND target_knowledge_point_id=? AND question_id=?
                   AND status='graded' AND id<>?
                   AND NOT EXISTS (
                       SELECT 1 FROM learner_diagnosis_session diagnosis
                        WHERE diagnosis.root_attempt_id=study_attempt.id
                          AND NOT EXISTS (SELECT 1 FROM learner_knowledge_evidence evidence
                                           WHERE evidence.attempt_id=study_attempt.id))
                 ORDER BY answered_at DESC,id DESC LIMIT 1
                """, (row, index) -> row.getString(1), learnerId, knowledgePointId, questionId,
                excludedAttemptId).stream().findFirst().orElse(null);
    }

    private Map<String, Exposure> exposures(String learnerId, Collection<String> questionIds) {
        List<Object> args = new ArrayList<>(); args.add(learnerId); args.addAll(questionIds);
        Map<String, Exposure> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT question_id,COUNT(*) exposure_count,MAX(created_at) last_exposed_at
                  FROM study_attempt WHERE learner_id=? AND question_id IN (%s)
                 GROUP BY question_id
                """.formatted(placeholders(questionIds.size())), row -> {
            result.put(row.getString("question_id"),
                    new Exposure(row.getInt("exposure_count"), instant(row.getTimestamp("last_exposed_at"))));
        },
                args.toArray());
        return result;
    }

    private JsonNode json(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("答题快照数据损坏。", error); }
    }

    private static double contribution(String assessment) {
        return "correct".equals(assessment) ? 1 : "partial".equals(assessment) ? .5 : 0;
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
    private record Exposure(int count, Instant lastExposedAt) {}
    private record Latest(String assessment, Instant answeredAt) {}
}

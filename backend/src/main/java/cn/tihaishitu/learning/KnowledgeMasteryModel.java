package cn.tihaishitu.learning;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.*;

/** Pure, deterministic model. All time is supplied by the caller. */
public final class KnowledgeMasteryModel {
    public record State(double masteryScore, double stabilityDays, int targetDifficulty, int evidenceCount,
                        int correctStreak, int wrongStreak, String lastOutcome, Instant lastEvidenceAt,
                        Instant lastCorrectAt, String modelVersion, long revision) {
        public static State initial() {
            return new State(0, INITIAL_STABILITY, INITIAL_DIFFICULTY, 0, 0, 0, null, null, null, MODEL_VERSION, 0);
        }
    }
    public record Evidence(String outcome, String gradingSource, String evidenceMode, int questionDifficulty,
                           Instant occurredAt) {
        public Evidence {
            if (!Set.of("correct", "partial", "wrong").contains(outcome)
                    || !Set.of("automatic", "self").contains(gradingSource)
                    || !Set.of("normal", "training").contains(evidenceMode)
                    || ("automatic".equals(gradingSource) && "partial".equals(outcome)) || occurredAt == null)
                throw new IllegalArgumentException("Invalid knowledge evidence");
        }
    }
    public record Calculation(State next, double quality, double learningRate, double effectiveMasteryBefore,
                              double stabilityBefore, int targetDifficultyBefore) {}

    public double effectiveMastery(State state, Instant now) {
        if (state.lastEvidenceAt() == null || state.evidenceCount() == 0) return 0;
        if (MODEL_VERSION.equals(state.modelVersion())) return round(state.masteryScore());
        return round(clamp(MIN_MASTERY, MAX_MASTERY, state.masteryScore()
                * Math.pow(2, -elapsedDays(state, now) / Math.max(MIN_STABILITY, state.stabilityDays()))));
    }

    public String band(State state, Instant now) {
        if (state.evidenceCount() == 0) return "unstarted";
        double effective = effectiveMastery(state, now);
        if (effective < LEARNING_THRESHOLD) return "unmastered";
        if (effective < READY_THRESHOLD) return "learning";
        return effective < PROFICIENT_THRESHOLD ? "ready" : "proficient";
    }

    public Calculation apply(State previous, Evidence evidence) {
        if (previous.lastEvidenceAt() != null && evidence.occurredAt().isBefore(previous.lastEvidenceAt()))
            throw new IllegalArgumentException("Evidence must be applied in chronological order");
        int difficulty = (int) clamp(MIN_DIFFICULTY, MAX_DIFFICULTY, evidence.questionDifficulty());
        double source = "self".equals(evidence.gradingSource()) ? SELF_FACTOR : AUTOMATIC_FACTOR;
        boolean training = "training".equals(evidence.evidenceMode());
        double mode = training ? TRAINING_FACTOR : NORMAL_FACTOR;
        double quality = switch (evidence.outcome()) {
            case "correct" -> "self".equals(evidence.gradingSource()) ? SELF_CORRECT_QUALITY : AUTOMATIC_CORRECT_QUALITY;
            case "partial" -> PARTIAL_QUALITY;
            default -> WRONG_QUALITY;
        };
        double directional = quality >= POSITIVE_QUALITY ? POSITIVE_BASE + DIFFICULTY_STEP * difficulty
                : quality <= NEGATIVE_QUALITY ? NEGATIVE_BASE - DIFFICULTY_STEP * difficulty : 1;
        double alpha = clamp(MIN_ALPHA, MAX_ALPHA, BASE_ALPHA * source * mode * directional);
        double before = effectiveMastery(previous, evidence.occurredAt());
        double after = round(clamp(MIN_MASTERY, MAX_MASTERY, before + alpha * (quality * MAX_MASTERY - before)));
        double stability = clamp(MIN_STABILITY, MAX_STABILITY, previous.stabilityDays());
        double spacing = previous.lastEvidenceAt() == null ? MAX_SPACING
                : clamp(MIN_SPACING, MAX_SPACING, elapsedDays(previous, evidence.occurredAt())
                / Math.max(stability, SPACING_STABILITY_FLOOR));
        double nextStability = switch (evidence.outcome()) {
            case "correct" -> stability * (1 + (SUCCESS_GAIN_BASE + SUCCESS_GAIN_STEP * difficulty) * source * mode * spacing);
            case "partial" -> stability * (1 - (1 - PARTIAL_RETENTION) * mode);
            default -> stability * (1 - (1 - (WRONG_RETENTION_BASE + WRONG_RETENTION_STEP * difficulty)) * mode);
        };
        int target = (int) clamp(MIN_DIFFICULTY, MAX_DIFFICULTY, previous.targetDifficulty());
        int correct = previous.correctStreak(), wrong = previous.wrongStreak();
        if (!training) {
            switch (evidence.outcome()) {
                case "correct" -> {
                    correct++; wrong = 0;
                    if (correct >= PROMOTION_STREAK && difficulty >= target) {
                        target = Math.min(MAX_DIFFICULTY, target + 1); correct = 0;
                    }
                }
                case "wrong" -> {
                    wrong++; correct = 0;
                    if (difficulty <= target) { target = Math.max(MIN_DIFFICULTY, target - 1); wrong = 0; }
                }
                default -> { correct = 0; wrong = 0; }
            }
        }
        State next = new State(after, round(clamp(MIN_STABILITY, MAX_STABILITY, nextStability)), target,
                previous.evidenceCount() + 1, correct, wrong, evidence.outcome(), evidence.occurredAt(),
                "correct".equals(evidence.outcome()) ? evidence.occurredAt() : previous.lastCorrectAt(),
                MODEL_VERSION, previous.revision() + 1);
        return new Calculation(next, quality, alpha, before, stability, previous.targetDifficulty());
    }

    private double elapsedDays(State state, Instant now) {
        return Math.max(0, Duration.between(state.lastEvidenceAt(), now).toMillis() / 1000d) / SECONDS_PER_DAY;
    }
}

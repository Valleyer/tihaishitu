package cn.tihaishitu.learning;

import java.time.Instant;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.MODEL_VERSION;

public final class QuestionCoverageMasteryModel {
    private final KnowledgeMasteryModel spacingModel = new KnowledgeMasteryModel();

    public KnowledgeMasteryModel.State rebuild(KnowledgeMasteryModel.State previous,
                                                LearnerQuestionProgressStore.Coverage coverage) {
        KnowledgeMasteryModel.State base = previous == null ? KnowledgeMasteryModel.State.initial() : previous;
        Instant lastCorrect = latest(base.lastCorrectAt(), coverage.lastCorrectAt());
        return new KnowledgeMasteryModel.State(coverage.masteryScore(), base.stabilityDays(),
                base.targetDifficulty(), coverage.seenQuestions(), base.correctStreak(), base.wrongStreak(),
                coverage.lastOutcome(), coverage.lastEvidenceAt(), lastCorrect, MODEL_VERSION,
                base.revision() + (MODEL_VERSION.equals(base.modelVersion()) ? 0 : 1));
    }

    public KnowledgeMasteryModel.Calculation apply(KnowledgeMasteryModel.State previous,
                                                   LearnerQuestionProgressStore.Coverage coverage,
                                                   KnowledgeMasteryModel.Evidence evidence) {
        KnowledgeMasteryModel.Calculation spaced = spacingModel.apply(previous, evidence);
        KnowledgeMasteryModel.State calculated = spaced.next();
        KnowledgeMasteryModel.State next = new KnowledgeMasteryModel.State(
                coverage.masteryScore(), calculated.stabilityDays(), calculated.targetDifficulty(),
                coverage.seenQuestions(), calculated.correctStreak(), calculated.wrongStreak(),
                coverage.lastOutcome(), evidence.occurredAt(),
                "correct".equals(evidence.outcome()) ? evidence.occurredAt() : previous.lastCorrectAt(),
                MODEL_VERSION, previous.revision() + 1);
        return new KnowledgeMasteryModel.Calculation(next, spaced.quality(), spaced.learningRate(),
                spaced.effectiveMasteryBefore(), spaced.stabilityBefore(), spaced.targetDifficultyBefore());
    }

    public boolean shouldApply(String previousAssessment, String outcome,
                               KnowledgeMasteryModel.State previous, Instant occurredAt) {
        if (previousAssessment == null || !previousAssessment.equals(outcome)) return true;
        return "correct".equals(outcome) && ReviewSchedulingPolicy.dueWithin24Hours(previous, occurredAt);
    }

    private static Instant latest(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }
}

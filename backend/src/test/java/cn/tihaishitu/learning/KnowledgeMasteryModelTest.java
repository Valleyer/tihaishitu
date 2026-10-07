package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeMasteryModelTest {
    private final KnowledgeMasteryModel model = new KnowledgeMasteryModel();

    @Test void chronologicalGuardStillRejectsTrulyOlderEvidence() {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        var state = new KnowledgeMasteryModel.State(30, 2, 2, 1, 1, 0, "correct", now, now,
                KnowledgeModelPolicy.MODEL_VERSION, 1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.apply(state,
                evidence("wrong", "automatic", "normal", 2, now.minusMillis(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Evidence must be applied in chronological order");
    }
    private final Instant day0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void decayUsesStabilityAsHalfLife() {
        var state = new KnowledgeMasteryModel.State(80, 10, 2, 1, 0, 0, "correct", day0, day0, "v1", 1);
        assertThat(model.effectiveMastery(state, day0.plusSeconds(10 * 86400))).isEqualTo(40);
        assertThat(model.effectiveMastery(state, day0.plusSeconds(20 * 86400))).isEqualTo(20);
        assertThat(state.masteryScore()).isEqualTo(80);
    }

    @Test
    void evidenceStrengthDirectionTrainingAndPartialFollowPolicy() {
        var initial = KnowledgeMasteryModel.State.initial();
        var normalCorrect = model.apply(initial, evidence("correct", "automatic", "normal", 3, day0));
        assertThat(normalCorrect.next().masteryScore()).isEqualTo(21);
        assertThat(normalCorrect.next().stabilityDays()).isEqualTo(2.2);

        var trainingCorrect = model.apply(initial, evidence("correct", "automatic", "training", 3, day0));
        assertThat(trainingCorrect.next().masteryScore()).isLessThan(normalCorrect.next().masteryScore());

        var high = new KnowledgeMasteryModel.State(80, 10, 2, 1, 0, 0, "correct", day0, day0, "v1", 1);
        var easyWrong = model.apply(high, evidence("wrong", "automatic", "normal", 1, day0));
        var hardWrong = model.apply(high, evidence("wrong", "automatic", "normal", 5, day0));
        assertThat(easyWrong.next().masteryScore()).isLessThan(hardWrong.next().masteryScore());
        assertThat(easyWrong.next().stabilityDays()).isLessThan(hardWrong.next().stabilityDays());

        var easyCorrect = model.apply(initial, evidence("correct", "automatic", "normal", 1, day0));
        var hardCorrect = model.apply(initial, evidence("correct", "automatic", "normal", 5, day0));
        assertThat(hardCorrect.next().masteryScore()).isGreaterThan(easyCorrect.next().masteryScore());

        var partial = model.apply(high, evidence("partial", "self", "normal", 3, day0));
        assertThat(partial.next().masteryScore()).isBetween(50d, 80d);
        assertThat(partial.quality()).isEqualTo(.5);
    }

    @Test
    void targetDifficultyStreaksAndClampsAreDeterministic() {
        var state = KnowledgeMasteryModel.State.initial();
        state = model.apply(state, evidence("correct", "automatic", "normal", 2, day0)).next();
        assertThat(state.targetDifficulty()).isEqualTo(2);
        state = model.apply(state, evidence("correct", "automatic", "normal", 2, day0.plusSeconds(86400))).next();
        assertThat(state.targetDifficulty()).isEqualTo(3);
        var training = model.apply(state, evidence("wrong", "automatic", "training", 1, day0.plusSeconds(2 * 86400))).next();
        assertThat(training.targetDifficulty()).isEqualTo(3);
        var lowered = model.apply(training, evidence("wrong", "automatic", "normal", 1, day0.plusSeconds(3 * 86400))).next();
        assertThat(lowered.targetDifficulty()).isEqualTo(2);
        assertThat(lowered.masteryScore()).isBetween(0d, 100d);
        assertThat(lowered.stabilityDays()).isBetween(.5d, 365d);
    }

    private KnowledgeMasteryModel.Evidence evidence(String outcome, String source, String mode, int difficulty, Instant at) {
        return new KnowledgeMasteryModel.Evidence(outcome, source, mode, difficulty, at);
    }
}

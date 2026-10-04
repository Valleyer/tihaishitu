package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ReviewSchedulingPolicyTest {
    private static final Instant LAST = Instant.parse("2026-10-04T00:00:00Z");

    @Test
    void derivesReviewWindowFromMasteryHalfLife() {
        assertThat(ReviewSchedulingPolicy.reviewDueAt(KnowledgeMasteryModel.State.initial())).isEmpty();
        assertThat(ReviewSchedulingPolicy.reviewDueAt(state(60, 10))).isEmpty();
        assertThat(ReviewSchedulingPolicy.reviewDueAt(state(70, 10))).contains(LAST);

        Instant due = ReviewSchedulingPolicy.reviewDueAt(state(90, 10)).orElseThrow();
        double days = Duration.between(LAST, due).toMillis() / 86_400_000d;
        assertThat(days).isCloseTo(3.63, within(.01));
        assertThat(ReviewSchedulingPolicy.status(state(90, 10), due.minus(Duration.ofHours(12))))
                .contains(ReviewSchedulingPolicy.ReviewStatus.SOON);
        assertThat(ReviewSchedulingPolicy.status(state(90, 10), due.plusSeconds(1)))
                .contains(ReviewSchedulingPolicy.ReviewStatus.DUE);
        assertThat(ReviewSchedulingPolicy.status(state(90, 100), LAST)).isEmpty();
    }

    private static KnowledgeMasteryModel.State state(double mastery, double stability) {
        return new KnowledgeMasteryModel.State(mastery, stability, 3, 1, 0, 0,
                "correct", LAST, LAST, "v1", 1);
    }
}

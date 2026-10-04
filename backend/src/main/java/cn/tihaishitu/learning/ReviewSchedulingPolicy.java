package cn.tihaishitu.learning;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.MIN_STABILITY;
import static cn.tihaishitu.learning.KnowledgeModelPolicy.READY_THRESHOLD;
import static cn.tihaishitu.learning.KnowledgeModelPolicy.SECONDS_PER_DAY;

/** Pure Phase H policy derived from the existing Mastery V1 half-life model. */
public final class ReviewSchedulingPolicy {
    public enum ReviewStatus { DUE, SOON, UPCOMING }

    private static final Duration SOON_WINDOW = Duration.ofHours(24);
    private static final Duration UPCOMING_WINDOW = Duration.ofDays(7);

    private ReviewSchedulingPolicy() {}

    public static boolean eligible(KnowledgeMasteryModel.State state) {
        return state != null && state.evidenceCount() > 0 && state.lastEvidenceAt() != null
                && state.masteryScore() >= READY_THRESHOLD;
    }

    public static Optional<Instant> reviewDueAt(KnowledgeMasteryModel.State state) {
        if (!eligible(state)) return Optional.empty();
        double stability = Math.max(MIN_STABILITY, state.stabilityDays());
        double daysUntilThreshold = stability
                * (Math.log(state.masteryScore() / READY_THRESHOLD) / Math.log(2));
        long millis = Math.round(daysUntilThreshold * SECONDS_PER_DAY * 1000d);
        return Optional.of(state.lastEvidenceAt().plusMillis(Math.max(0, millis)));
    }

    public static Optional<ReviewStatus> status(KnowledgeMasteryModel.State state, Instant now) {
        Optional<Instant> due = reviewDueAt(state);
        if (due.isEmpty()) return Optional.empty();
        if (!due.get().isAfter(now)) return Optional.of(ReviewStatus.DUE);
        if (!due.get().isAfter(now.plus(SOON_WINDOW))) return Optional.of(ReviewStatus.SOON);
        if (!due.get().isAfter(now.plus(UPCOMING_WINDOW))) return Optional.of(ReviewStatus.UPCOMING);
        return Optional.empty();
    }

    public static boolean dueWithin24Hours(KnowledgeMasteryModel.State state, Instant now) {
        return reviewDueAt(state).map(due -> !due.isAfter(now.plus(SOON_WINDOW))).orElse(false);
    }
}

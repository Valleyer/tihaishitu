package cn.tihaishitu.learning;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.game.KnowledgeQuestionPoolStore;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.learner.StudyProfileStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.READY_THRESHOLD;

@Service
public class ReviewQueueService {
    private final StudyProfileStore profiles;
    private final KnowledgeQuestionPoolStore pool;
    private final LearnerKnowledgeStateStore states;
    private final KnowledgeMasteryModel model = new KnowledgeMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public ReviewQueueService(StudyProfileStore profiles, KnowledgeQuestionPoolStore pool,
                              LearnerKnowledgeStateStore states) {
        this.profiles = profiles;
        this.pool = pool;
        this.states = states;
    }

    public ReviewQueue current() {
        return queueAt(LearnerContext.learnerId(), clock.instant());
    }

    ReviewQueue queueAt(String learnerId, Instant now) {
        List<String> selectedBooks = profiles.find(learnerId).selectedBookIds();
        if (selectedBooks.isEmpty()) return empty(now);

        List<KnowledgePointDto> scope = pool.bookScope(new LinkedHashSet<>(selectedBooks));
        Map<String, KnowledgePointDto> pointById = new LinkedHashMap<>();
        scope.forEach(point -> pointById.putIfAbsent(point.id(), point));
        Set<String> allowed = new LinkedHashSet<>(pointById.keySet());

        Map<String, KnowledgeMasteryModel.State> stateByPoint = new HashMap<>();
        states.findForKnowledgePoints(learnerId, allowed)
                .forEach(row -> stateByPoint.put(row.knowledgePointId(), row.state()));
        Set<String> ready = new LinkedHashSet<>();
        stateByPoint.forEach((id, state) -> {
            if (model.effectiveMastery(state, now) >= READY_THRESHOLD) ready.add(id);
        });
        Set<String> playable = pool.adaptivePlayableKnowledgePointIds(allowed, ready);

        List<ReviewItem> items = new ArrayList<>();
        pointById.forEach((id, point) -> {
            KnowledgeMasteryModel.State state = stateByPoint.get(id);
            ReviewSchedulingPolicy.status(state, now).ifPresent(status -> items.add(new ReviewItem(
                    id, point.name(), point.subject(), point.category(), chapter(point),
                    model.effectiveMastery(state, now), state.stabilityDays(), state.targetDifficulty(),
                    state.lastEvidenceAt(), ReviewSchedulingPolicy.reviewDueAt(state).orElseThrow(),
                    status.name().toLowerCase(Locale.ROOT), playable.contains(id))));
        });
        items.sort(Comparator.comparingInt((ReviewItem item) -> statusRank(item.status()))
                .thenComparing(ReviewItem::reviewDueAt)
                .thenComparingDouble(ReviewItem::effectiveMastery)
                .thenComparing(ReviewItem::name)
                .thenComparing(ReviewItem::knowledgePointId));

        int due = 0, soon = 0, upcoming = 0, playableDueOrSoon = 0;
        for (ReviewItem item : items) {
            switch (item.status()) {
                case "due" -> due++;
                case "soon" -> soon++;
                default -> upcoming++;
            }
            if (item.playable() && !"upcoming".equals(item.status())) playableDueOrSoon++;
        }
        return new ReviewQueue(now, new ReviewSummary(due, soon, upcoming, playableDueOrSoon), List.copyOf(items));
    }

    private static ReviewQueue empty(Instant now) {
        return new ReviewQueue(now, new ReviewSummary(0, 0, 0, 0), List.of());
    }

    private static String chapter(KnowledgePointDto point) {
        return point.tags().isEmpty() ? "" : point.tags().get(0);
    }

    private static int statusRank(String status) {
        return switch (status) { case "due" -> 0; case "soon" -> 1; default -> 2; };
    }

    public record ReviewQueue(Instant generatedAt, ReviewSummary summary, List<ReviewItem> items) {}
    public record ReviewSummary(int due, int soon, int upcoming, int playableDueOrSoon) {}
    public record ReviewItem(String knowledgePointId, String name, String subject, String section, String chapter,
                             double effectiveMastery, double stabilityDays, int targetDifficulty,
                             Instant lastEvidenceAt, Instant reviewDueAt, String status, boolean playable) {}
}

package cn.tihaishitu.learning;

import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.learner.LearnerStore;
import cn.tihaishitu.world.WorldActionContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.READY_THRESHOLD;

@Service
public class LearnerKnowledgeStateService {
    private final LearnerKnowledgeStateStore store;
    private final LearnerStore learners;
    private final LearnerQuestionMasteryStore questionMastery;
    private final KnowledgeMasteryModel model = new KnowledgeMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public LearnerKnowledgeStateService(LearnerKnowledgeStateStore store, LearnerStore learners,
                                        LearnerQuestionMasteryStore questionMastery) {
        this.store = store;
        this.learners = learners;
        this.questionMastery = questionMastery;
    }

    /** Formal World grading acquires this lock before reading the final attempt snapshot. */
    public void lockCurrentLearnerForGrading() {
        WorldActionContext.Scope world = WorldActionContext.currentOrNull();
        if (world != null) learners.lockForUpdate(world.learnerId());
    }

    public void apply(QuestionAttemptStore.Snapshot attempt, String outcome, String gradingSource, Instant occurredAt) {
        if (attempt.learnerId() == null) return;
        if (attempt.targetKnowledgePointId() == null || attempt.evidenceMode() == null
                || attempt.questionDifficulty() == null)
            throw new IllegalStateException("正式学习 attempt 缺少知识证据上下文。");
        if (!questionMastery.isFormalQuestion(attempt.questionId())) return;
        learners.lockForUpdate(attempt.learnerId());
        if (store.evidenceExists(attempt.id())) return;
        var previous = store.find(attempt.learnerId(), attempt.targetKnowledgePointId())
                .orElse(KnowledgeMasteryModel.State.initial());
        boolean migrated = false;
        if (!KnowledgeModelPolicy.MODEL_VERSION.equals(previous.modelVersion())) {
            questionMastery.rebuild(attempt.learnerId(), attempt.targetKnowledgePointId(), occurredAt);
            previous = projectionState(previous, questionMastery.projection(
                    attempt.learnerId(), attempt.targetKnowledgePointId(), occurredAt));
            store.save(attempt.learnerId(), attempt.targetKnowledgePointId(), previous);
            migrated = true;
        }
        var evidence = new KnowledgeMasteryModel.Evidence(outcome, gradingSource, attempt.evidenceMode(),
                attempt.questionDifficulty(), occurredAt);
        var mastery = questionMastery.apply(attempt.learnerId(), attempt.targetKnowledgePointId(),
                attempt.questionId(), outcome, occurredAt);
        if (!mastery.effective() && !migrated) return;
        var spaced = model.apply(previous, evidence);
        var projected = projectionState(spaced.next(), mastery.projection());
        var calculation = new KnowledgeMasteryModel.Calculation(projected, spaced.quality(), spaced.learningRate(),
                spaced.effectiveMasteryBefore(), spaced.stabilityBefore(), spaced.targetDifficultyBefore());
        store.insertEvidence(UUID.randomUUID().toString(), attempt.learnerId(), attempt.targetKnowledgePointId(),
                attempt.id(), attempt.questionId(), attempt.worldId(), evidence, calculation);
        store.save(attempt.learnerId(), attempt.targetKnowledgePointId(), calculation.next());
    }

    @Transactional
    public StateView current(String pointId) {
        if (!store.activeKnowledgeExists(pointId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识点不存在或已停用。");
        String learnerId = LearnerContext.learnerId();
        Instant now = clock.instant();
        return view(pointId, settle(learnerId, pointId, store.find(learnerId, pointId).orElse(null), now), now);
    }

    @Transactional
    public List<StateView> currentForBook(String bookId) {
        if (!store.enabledBookExists(bookId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文集不存在或已停用。");
        List<LearnerKnowledgeStateStore.StateRow> rows = store.findForBook(LearnerContext.learnerId(), bookId);
        Instant now = clock.instant();
        String learnerId = LearnerContext.learnerId();
        return rows.stream().map(row -> view(row.knowledgePointId(),
                settle(learnerId, row.knowledgePointId(), row.state(), now), now)).toList();
    }

    @Transactional
    public Map<String, KnowledgeMasteryModel.State> settledStates(String learnerId, Set<String> pointIds,
                                                                   Instant now) {
        Map<String, KnowledgeMasteryModel.State> result = new LinkedHashMap<>();
        if (pointIds.isEmpty()) return result;
        List<LearnerKnowledgeStateStore.StateRow> rows = store.findForKnowledgePoints(learnerId, pointIds);
        if (!rows.isEmpty()) learners.lockForUpdate(learnerId);
        rows.forEach(row -> result.put(row.knowledgePointId(),
                settle(learnerId, row.knowledgePointId(), row.state(), now)));
        return result;
    }

    @Transactional
    public void mergeKnowledge(String sourceId, String targetId) {
        List<String> learners = store.affectedLearners(sourceId, targetId);
        learners.forEach(this.learners::lockForUpdate);
        store.canonicalizeForMerge(sourceId, targetId);
        questionMastery.deleteForPoint(sourceId);
        for (String learnerId : learners) {
            var previous = store.find(learnerId, targetId).orElse(KnowledgeMasteryModel.State.initial());
            questionMastery.rebuild(learnerId, targetId, clock.instant());
            var state = projectionState(previous, questionMastery.projection(learnerId, targetId, clock.instant()));
            store.deleteState(learnerId, targetId);
            if (state.evidenceCount() > 0) store.save(learnerId, targetId, state);
        }
    }

    @Transactional
    public void rebuildCoverage(String pointId) {
        for (String learnerId : store.affectedLearners(pointId, pointId)) {
            learners.lockForUpdate(learnerId);
            var previous = store.find(learnerId, pointId).orElse(KnowledgeMasteryModel.State.initial());
            questionMastery.rebuild(learnerId, pointId, clock.instant());
            var rebuilt = projectionState(previous, questionMastery.projection(learnerId, pointId, clock.instant()));
            store.deleteState(learnerId, pointId);
            if (rebuilt.evidenceCount() > 0) store.save(learnerId, pointId, rebuilt);
        }
    }

    @Transactional
    public void rebuildLegacyStates() {
        for (var identity : store.legacyStates()) {
            learners.lockForUpdate(identity.learnerId());
            var existing = store.find(identity.learnerId(), identity.knowledgePointId()).orElse(null);
            if (existing != null) {
                questionMastery.rebuild(identity.learnerId(), identity.knowledgePointId(), clock.instant());
                store.save(identity.learnerId(), identity.knowledgePointId(), projectionState(existing,
                        questionMastery.projection(identity.learnerId(), identity.knowledgePointId(), clock.instant())));
            }
        }
    }

    private KnowledgeMasteryModel.State settle(String learnerId, String pointId,
                                                KnowledgeMasteryModel.State state, Instant now) {
        if (state == null) return null;
        if (!KnowledgeModelPolicy.MODEL_VERSION.equals(state.modelVersion()))
            questionMastery.rebuild(learnerId, pointId, now);
        KnowledgeMasteryModel.State projected = projectionState(state,
                questionMastery.projection(learnerId, pointId, now));
        if (!sameProjection(state, projected)) store.save(learnerId, pointId, projected);
        return sameProjection(state, projected) ? state : projected;
    }

    private static boolean sameProjection(KnowledgeMasteryModel.State left, KnowledgeMasteryModel.State right) {
        return Double.compare(left.masteryScore(), right.masteryScore()) == 0
                && left.evidenceCount() == right.evidenceCount()
                && Objects.equals(left.lastOutcome(), right.lastOutcome())
                && Objects.equals(left.lastEvidenceAt(), right.lastEvidenceAt())
                && Objects.equals(left.lastCorrectAt(), right.lastCorrectAt())
                && Objects.equals(left.modelVersion(), right.modelVersion());
    }

    private KnowledgeMasteryModel.State projectionState(KnowledgeMasteryModel.State base,
            LearnerQuestionMasteryStore.Projection projection) {
        return new KnowledgeMasteryModel.State(projection.masteryScore(), base.stabilityDays(),
                base.targetDifficulty(), projection.evidenceCount(), base.correctStreak(), base.wrongStreak(),
                projection.lastAssessment(), projection.lastAttemptAt(), projection.lastCorrectAt(),
                KnowledgeModelPolicy.MODEL_VERSION, base.revision() + 1);
    }

    private StateView view(String pointId, KnowledgeMasteryModel.State state, Instant now) {
        if (state == null) state = KnowledgeMasteryModel.State.initial();
        double effective = model.effectiveMastery(state, now);
        return new StateView(pointId, state.masteryScore(), effective, state.stabilityDays(), model.band(state, now),
                effective >= READY_THRESHOLD, state.targetDifficulty(), state.evidenceCount(), state.correctStreak(),
                state.wrongStreak(), state.lastOutcome(), state.lastEvidenceAt(), state.lastCorrectAt(),
                state.modelVersion(), state.revision());
    }

    public record StateView(String knowledgePointId, double masteryScore, double effectiveMastery,
                            double stabilityDays, String band, boolean ready, int targetDifficulty,
                            int evidenceCount, int correctStreak, int wrongStreak, String lastOutcome,
                            Instant lastEvidenceAt, Instant lastCorrectAt, String modelVersion, long revision) {}
}

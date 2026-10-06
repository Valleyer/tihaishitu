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
import java.util.List;
import java.util.UUID;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.READY_THRESHOLD;

@Service
public class LearnerKnowledgeStateService {
    private final LearnerKnowledgeStateStore store;
    private final LearnerStore learners;
    private final LearnerQuestionProgressStore questionProgress;
    private final KnowledgeMasteryModel model = new KnowledgeMasteryModel();
    private final QuestionCoverageMasteryModel coverageModel = new QuestionCoverageMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public LearnerKnowledgeStateService(LearnerKnowledgeStateStore store, LearnerStore learners,
                                        LearnerQuestionProgressStore questionProgress) {
        this.store = store;
        this.learners = learners;
        this.questionProgress = questionProgress;
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
        learners.lockForUpdate(attempt.learnerId());
        if (store.evidenceExists(attempt.id())) return;
        var previous = store.find(attempt.learnerId(), attempt.targetKnowledgePointId())
                .orElse(KnowledgeMasteryModel.State.initial());
        if (!KnowledgeModelPolicy.MODEL_VERSION.equals(previous.modelVersion())) {
            previous = coverageModel.rebuild(previous, questionProgress.coverage(
                    attempt.learnerId(), attempt.targetKnowledgePointId(), attempt.id()));
            store.save(attempt.learnerId(), attempt.targetKnowledgePointId(), previous);
        }
        String previousAssessment = questionProgress.latestAssessmentBeforeAttempt(attempt.learnerId(),
                attempt.targetKnowledgePointId(), attempt.questionId(), attempt.id());
        var evidence = new KnowledgeMasteryModel.Evidence(outcome, gradingSource, attempt.evidenceMode(),
                attempt.questionDifficulty(), occurredAt);
        if (!coverageModel.shouldApply(previousAssessment, outcome, previous, occurredAt)) return;
        var calculation = coverageModel.apply(previous,
                questionProgress.coverageIncludingAttempt(attempt.learnerId(),
                        attempt.targetKnowledgePointId(), attempt.id()), evidence);
        store.insertEvidence(UUID.randomUUID().toString(), attempt.learnerId(), attempt.targetKnowledgePointId(),
                attempt.id(), attempt.questionId(), attempt.worldId(), evidence, calculation);
        store.save(attempt.learnerId(), attempt.targetKnowledgePointId(), calculation.next());
    }

    public StateView current(String pointId) {
        if (!store.activeKnowledgeExists(pointId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识点不存在或已停用。");
        String learnerId = LearnerContext.learnerId();
        return view(pointId, ensureV2(learnerId, pointId, store.find(learnerId, pointId).orElse(null)), clock.instant());
    }

    public List<StateView> currentForBook(String bookId) {
        if (!store.enabledBookExists(bookId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文集不存在或已停用。");
        List<LearnerKnowledgeStateStore.StateRow> rows = store.findForBook(LearnerContext.learnerId(), bookId);
        Instant now = clock.instant();
        String learnerId = LearnerContext.learnerId();
        return rows.stream().map(row -> view(row.knowledgePointId(),
                ensureV2(learnerId, row.knowledgePointId(), row.state()), now)).toList();
    }

    @Transactional
    public void mergeKnowledge(String sourceId, String targetId) {
        List<String> learners = store.affectedLearners(sourceId, targetId);
        learners.forEach(this.learners::lockForUpdate);
        store.canonicalizeForMerge(sourceId, targetId);
        for (String learnerId : learners) {
            var previous = store.find(learnerId, targetId).orElse(KnowledgeMasteryModel.State.initial());
            var state = coverageModel.rebuild(previous, questionProgress.coverage(learnerId, targetId));
            store.deleteState(learnerId, targetId);
            if (state.evidenceCount() > 0) store.save(learnerId, targetId, state);
        }
    }

    @Transactional
    public void rebuildCoverage(String pointId) {
        for (String learnerId : store.affectedLearners(pointId, pointId)) {
            learners.lockForUpdate(learnerId);
            var previous = store.find(learnerId, pointId).orElse(KnowledgeMasteryModel.State.initial());
            var rebuilt = coverageModel.rebuild(previous, questionProgress.coverage(learnerId, pointId));
            store.deleteState(learnerId, pointId);
            if (rebuilt.evidenceCount() > 0) store.save(learnerId, pointId, rebuilt);
        }
    }

    @Transactional
    public void rebuildLegacyStates() {
        for (var identity : store.legacyStates()) {
            learners.lockForUpdate(identity.learnerId());
            var existing = store.find(identity.learnerId(), identity.knowledgePointId()).orElse(null);
            if (existing != null) store.save(identity.learnerId(), identity.knowledgePointId(),
                    coverageModel.rebuild(existing,
                            questionProgress.coverage(identity.learnerId(), identity.knowledgePointId())));
        }
    }

    private KnowledgeMasteryModel.State ensureV2(String learnerId, String pointId,
                                                   KnowledgeMasteryModel.State state) {
        if (state == null || KnowledgeModelPolicy.MODEL_VERSION.equals(state.modelVersion())) return state;
        KnowledgeMasteryModel.State rebuilt = coverageModel.rebuild(state, questionProgress.coverage(learnerId, pointId));
        store.save(learnerId, pointId, rebuilt);
        return rebuilt;
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

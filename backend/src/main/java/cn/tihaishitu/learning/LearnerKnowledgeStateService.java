package cn.tihaishitu.learning;

import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learner.LearnerContext;
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
    private final KnowledgeMasteryModel model = new KnowledgeMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public LearnerKnowledgeStateService(LearnerKnowledgeStateStore store) { this.store = store; }

    /** Serializes final grading for one Learner before the attempt and aggregate facts are written. */
    public void lockForGrading(QuestionAttemptStore.Snapshot attempt) {
        if (attempt.learnerId() != null) store.lockLearner(attempt.learnerId());
    }

    public void apply(QuestionAttemptStore.Snapshot attempt, String outcome, String gradingSource, Instant occurredAt) {
        if (attempt.learnerId() == null) return;
        if (attempt.targetKnowledgePointId() == null || attempt.evidenceMode() == null
                || attempt.questionDifficulty() == null)
            throw new IllegalStateException("正式学习 attempt 缺少知识证据上下文。");
        store.lockLearner(attempt.learnerId());
        if (store.evidenceExists(attempt.id())) return;
        var previous = store.find(attempt.learnerId(), attempt.targetKnowledgePointId())
                .orElse(KnowledgeMasteryModel.State.initial());
        var evidence = new KnowledgeMasteryModel.Evidence(outcome, gradingSource, attempt.evidenceMode(),
                attempt.questionDifficulty(), occurredAt);
        var calculation = model.apply(previous, evidence);
        store.insertEvidence(UUID.randomUUID().toString(), attempt.learnerId(), attempt.targetKnowledgePointId(),
                attempt.id(), attempt.questionId(), attempt.worldId(), evidence, calculation);
        store.save(attempt.learnerId(), attempt.targetKnowledgePointId(), calculation.next());
    }

    public StateView current(String pointId) {
        if (!store.activeKnowledgeExists(pointId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "知识点不存在或已停用。");
        return view(pointId, store.find(LearnerContext.learnerId(), pointId).orElse(null), clock.instant());
    }

    public List<StateView> currentForBook(String bookId) {
        if (!store.enabledBookExists(bookId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文集不存在或已停用。");
        List<LearnerKnowledgeStateStore.StateRow> rows = store.findForBook(LearnerContext.learnerId(), bookId);
        Instant now = clock.instant();
        return rows.stream().map(row -> view(row.knowledgePointId(), row.state(), now)).toList();
    }

    @Transactional
    public void mergeKnowledge(String sourceId, String targetId) {
        List<String> learners = store.affectedLearners(sourceId, targetId);
        learners.forEach(store::lockLearner);
        store.canonicalizeForMerge(sourceId, targetId);
        for (String learnerId : learners) {
            var state = KnowledgeMasteryModel.State.initial();
            for (var row : store.evidenceForReplay(learnerId, targetId)) {
                var evidence = new KnowledgeMasteryModel.Evidence(row.outcome(), row.gradingSource(), row.evidenceMode(),
                        row.questionDifficulty(), row.occurredAt());
                var calculation = model.apply(state, evidence);
                store.updateReplayCalculation(row.id(), calculation);
                state = calculation.next();
            }
            store.deleteState(learnerId, targetId);
            if (state.evidenceCount() > 0) store.save(learnerId, targetId, state);
        }
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

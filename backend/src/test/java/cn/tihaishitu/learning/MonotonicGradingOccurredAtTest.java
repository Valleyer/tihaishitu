package cn.tihaishitu.learning;

import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learner.LearnerStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MonotonicGradingOccurredAtTest {
    @Test void candidateBeforePersistedEvidenceUsesPersistedTimeUnderLearnerLock() {
        LearnerKnowledgeStateStore store = mock(LearnerKnowledgeStateStore.class);
        LearnerStore learners = mock(LearnerStore.class);
        LearnerQuestionMasteryStore mastery = mock(LearnerQuestionMasteryStore.class);
        LearnerKnowledgeStateService service = new LearnerKnowledgeStateService(store, learners, mastery);
        Instant persisted = Instant.parse("2026-10-08T01:00:00.123456Z");
        KnowledgeMasteryModel.State state = new KnowledgeMasteryModel.State(30, 2, 2, 1, 1, 0,
                "correct", persisted, persisted, KnowledgeModelPolicy.MODEL_VERSION, 1);
        when(store.find("learner", "point")).thenReturn(Optional.of(state));
        QuestionAttemptStore.Snapshot attempt = new QuestionAttemptStore.Snapshot("attempt", null, "learner",
                "ancient-official", null, "question", null, null, "active", "auto", null, null,
                "point", "normal", 2, null, null, null, "random", "oldest");

        Instant normalized = service.normalizeGradingOccurredAt(attempt, persisted.minusMillis(1));

        assertThat(normalized).isEqualTo(persisted);
        verify(learners).lockForUpdate("learner");
    }
}

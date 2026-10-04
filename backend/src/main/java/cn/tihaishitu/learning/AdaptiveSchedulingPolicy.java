package cn.tihaishitu.learning;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.MAX_DIFFICULTY;
import static cn.tihaishitu.learning.KnowledgeModelPolicy.MIN_DIFFICULTY;
import static cn.tihaishitu.learning.KnowledgeModelPolicy.PROFICIENT_THRESHOLD;
import static cn.tihaishitu.learning.KnowledgeModelPolicy.READY_THRESHOLD;

/** Explainable V1 scheduling rules. Phase E mastery parameters remain owned by KnowledgeModelPolicy. */
public final class AdaptiveSchedulingPolicy {
    private AdaptiveSchedulingPolicy() {}

    public static int targetPriority(KnowledgeMasteryModel.State state, double effectiveMastery) {
        if (state == null || state.evidenceCount() == 0) return 1;
        if (effectiveMastery < READY_THRESHOLD) return 0;
        return effectiveMastery < PROFICIENT_THRESHOLD ? 2 : 3;
    }

    public static int preferredDifficulty(KnowledgeMasteryModel.State state, double effectiveMastery,
                                          String profileDifficulty) {
        int base = state == null || state.evidenceCount() == 0 ? 2
                : Math.max(MIN_DIFFICULTY, Math.min(MAX_DIFFICULTY, state.targetDifficulty()));
        int cap;
        if (state == null || state.evidenceCount() == 0 || effectiveMastery < 40) cap = 2;
        else if (effectiveMastery < READY_THRESHOLD) cap = 3;
        else if (effectiveMastery < PROFICIENT_THRESHOLD) cap = 4;
        else cap = 5;
        int standard = Math.min(base, cap);
        return "gentle".equals(profileDifficulty) ? Math.max(MIN_DIFFICULTY, standard - 1) : standard;
    }
}

package cn.tihaishitu.learning;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * WRONG 正式选题策略：单题错题重做（wrong_review）与错题快练（wrong_drill）。
 *
 * <p>错题是永久资产：正式父题出现过 wrong / partial 即写入永久错题本，
 * 之后答对**不会**自动移出，只有用户手动移出才 {@code status='removed'}。</p>
 *
 * <p>wrong_drill 候选：{@code learner_wrong_question.status='active'} +
 * 当前 Session 冻结 scope 可用 + published Formal Parent。
 * Session 内随机且不重复，全部耗尽后本轮完成，新开 Session 重新对完整池洗牌；
 * 用户手动移出后 future Session 立即不再包含该题。</p>
 *
 * <p>候选与 {@code hasRemaining} 一律以调用方传入的冻结
 * {@code allowedKnowledgePointIds} 为准，不读取实时 {@code learner_selected_book}：
 * 已经开始的 active Session 不会因为 Learner 在别处改了学习范围而换题池或错报 canRepeat。</p>
 *
 * <p>wrong_review 从错题列表点指定题时直接发该题，graded 后流程完成，
 * 不进入任何诊断或补救子题；历史 target 当前仍合法时沿用，否则选择当前 scope 内
 * 的稳定合法绑定，且不改写永久错题的历史归因。</p>
 */
@Service
public class WrongPracticeSelector {
    public record Selection(String questionId, String targetKnowledgePointId) {}

    private final LearnerPracticeStore store;
    private final KnowledgeQuestionPoolService pool;

    public WrongPracticeSelector(LearnerPracticeStore store, KnowledgeQuestionPoolService pool) {
        this.store = store;
        this.pool = pool;
    }

    /** 本 Session 还未见过的 active 错题候选（限定冻结 scope）。 */
    public List<String> candidateQuestionIds(String learnerId, Set<String> allowedKnowledgePointIds,
                                             Set<String> seenQuestionIds) {
        return store.wrongDrillQuestionIds(learnerId, allowedKnowledgePointIds, seenQuestionIds);
    }

    /** Session 内随机不重复地取下一道可用 active 错题；候选耗尽返回空。 */
    public Optional<Selection> select(String learnerId, Set<String> allowedKnowledgePointIds, Set<String> seenQuestionIds) {
        List<LearnerPracticeStore.WrongPracticeCandidate> candidates = new ArrayList<>(
                store.wrongDrillCandidates(learnerId, allowedKnowledgePointIds, seenQuestionIds));
        while (!candidates.isEmpty()) {
            LearnerPracticeStore.WrongPracticeCandidate candidate = candidates.remove(
                    ThreadLocalRandom.current().nextInt(candidates.size()));
            // 题目可能已经被下架、解绑或离开当前 Session 冻结范围：跳过而不是 500。
            if (pool.questionForLearner(candidate.targetKnowledgePointId(), allowedKnowledgePointIds,
                    candidate.questionId()).isEmpty()) continue;
            return Optional.of(new Selection(candidate.questionId(), candidate.targetKnowledgePointId()));
        }
        return Optional.empty();
    }

    /** 单题重做：历史 target 合法则沿用，否则使用当前范围内的稳定有效绑定。 */
    public Optional<Selection> selectQuestion(String learnerId, Set<String> allowedKnowledgePointIds,
                                              String questionId) {
        return store.wrongDrillCandidates(learnerId, allowedKnowledgePointIds, Set.of()).stream()
                .filter(candidate -> candidate.questionId().equals(questionId))
                .filter(candidate -> pool.questionForLearner(candidate.targetKnowledgePointId(),
                        allowedKnowledgePointIds, questionId).isPresent())
                .findFirst()
                .map(candidate -> new Selection(candidate.questionId(), candidate.targetKnowledgePointId()));
    }

    /**
     * 本 Session 是否还有没练过的 active 错题（决定 canRepeat）。
     * 口径与 {@link #select} 完全一致，避免“显示还能继续但 next 报错”。
     */
    public boolean hasRemaining(String learnerId, Set<String> allowedKnowledgePointIds, Set<String> seenQuestionIds) {
        return select(learnerId, allowedKnowledgePointIds, seenQuestionIds).isPresent();
    }
}

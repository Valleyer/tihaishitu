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
 * 当前 selected Books 范围可用 + published Formal Parent。
 * Session 内随机且不重复，全部耗尽后本轮完成，新开 Session 重新对完整池洗牌；
 * 用户手动移出后 future Session 立即不再包含该题。</p>
 *
 * <p>wrong_review 从错题列表点指定题时直接发该题，graded 后流程完成，
 * 不进入任何诊断或补救子题。</p>
 */
@Service
public class WrongPracticeSelector {
    public record Selection(String questionId, String targetKnowledgePointId) {}

    private final LearnerPracticeStore store;
    private final PracticeSelectionStore selections;
    private final KnowledgeQuestionPoolService pool;

    public WrongPracticeSelector(LearnerPracticeStore store, PracticeSelectionStore selections,
                                 KnowledgeQuestionPoolService pool) {
        this.store = store;
        this.selections = selections;
        this.pool = pool;
    }

    /** 本 Session 还未见过的 active 错题候选。 */
    public List<String> candidateQuestionIds(String learnerId, Set<String> seenQuestionIds) {
        return store.wrongDrillQuestionIds(learnerId, seenQuestionIds);
    }

    /** Session 内随机不重复地取下一道可用 active 错题；候选耗尽返回空。 */
    public Optional<Selection> select(String learnerId, Set<String> allowedKnowledgePointIds, Set<String> seenQuestionIds) {
        List<String> candidates = new ArrayList<>(candidateQuestionIds(learnerId, seenQuestionIds));
        while (!candidates.isEmpty()) {
            String questionId = candidates.remove(ThreadLocalRandom.current().nextInt(candidates.size()));
            Optional<String> target = selections.activeWrongTargetKnowledgePoint(learnerId, questionId);
            if (target.isEmpty()) continue;
            // 题目可能已经被下架、解绑或离开当前 Session 冻结范围：跳过而不是 500。
            if (pool.questionForLearner(target.get(), allowedKnowledgePointIds, questionId).isEmpty()) continue;
            return Optional.of(new Selection(questionId, target.get()));
        }
        return Optional.empty();
    }

    /** 本 Session 是否还有没练过的 active 错题（决定 canRepeat）。 */
    public boolean hasRemaining(String learnerId, Set<String> seenQuestionIds) {
        return !candidateQuestionIds(learnerId, seenQuestionIds).isEmpty();
    }
}

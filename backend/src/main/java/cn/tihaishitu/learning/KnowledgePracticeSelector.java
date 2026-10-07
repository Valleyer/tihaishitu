package cn.tihaishitu.learning;

import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * KNOWLEDGE 正式选题策略：知识点专项。
 *
 * <p>候选：该 KnowledgePoint 全部 published Formal Parent Question（core + auxiliary 都算），
 * 且属于当前冻结的 selected Books 范围。</p>
 *
 * <p>选择：Session 内随机且不重复，等价于每个 Session 一轮随机 permutation。
 * 不要求预先把队列落库，只从“本 Session 尚未 seen”的候选里随机一题；
 * 全部耗尽后本轮完成，新开 Session 重新对完整池洗牌。</p>
 *
 * <p>禁止 Mastery / readiness / Review / preferred difficulty 影响候选资格或排序。</p>
 */
@Service
public class KnowledgePracticeSelector {
    private final KnowledgeQuestionPoolService pool;

    public KnowledgePracticeSelector(KnowledgeQuestionPoolService pool) { this.pool = pool; }

    /** 本 Session 还未见过的专项候选（顺序只由候选查询决定，不代表出题顺序）。 */
    public List<String> candidateQuestionIds(String knowledgePointId, Set<String> allowedKnowledgePointIds,
                                             Set<String> seenQuestionIds) {
        return pool.eligibleFormalQuestions(knowledgePointId, allowedKnowledgePointIds, seenQuestionIds)
                .stream().map(QuestionDto::id).toList();
    }

    /** Session 内随机不重复地取下一题；候选耗尽返回空。 */
    public Optional<String> select(String knowledgePointId, Set<String> allowedKnowledgePointIds,
                                  Set<String> seenQuestionIds) {
        List<String> candidates = candidateQuestionIds(knowledgePointId, allowedKnowledgePointIds, seenQuestionIds);
        if (candidates.isEmpty()) return Optional.empty();
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }

    /** 本 Session 是否还有没做过的专项题（决定 canRepeat）。 */
    public boolean hasRemaining(String knowledgePointId, Set<String> allowedKnowledgePointIds,
                                Set<String> seenQuestionIds) {
        return !candidateQuestionIds(knowledgePointId, allowedKnowledgePointIds, seenQuestionIds).isEmpty();
    }
}

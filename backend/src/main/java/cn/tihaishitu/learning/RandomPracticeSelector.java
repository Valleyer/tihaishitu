package cn.tihaishitu.learning;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RANDOM 正式选题策略：Learner World / 寒门仕途普通随机正式题。
 *
 * <p>长期规则（与旧的“Book pool 直接 uniform random Question”完全不同）：</p>
 *
 * <pre>
 * 1. 先选 target KnowledgePoint，再在该 KP 内选题（KP-first，不做 readiness / Mastery gating）
 * 2. 当天第一次 RANDOM 与上一题 correct 后的切换：按持久化次序交替请求 ALL / WRONG KP 池
 *    （WRONG 无候选时回退 ALL 但仍消费本槽；只剩一个 eligible KP 时允许继续同 KP）
 * 4. 上一题 wrong / partial：优先留在原 target KP，换该 KP 内另一道当天未出的题
 * 5. 同一 Learner × 同一 Asia/Shanghai 业务日，同一 Question 最多创建一次 RANDOM Attempt
 *    （active / revealed / graded 都算“已经出过”，发出即占额度）
 * 6. 每个 Learner × KnowledgePoint 内 oldest / wrong 交替，第一次进入为 oldest
 * </pre>
 *
 * <p>禁止按 Mastery、readiness、Review due、preferred difficulty、dependency gating 或
 * 历史答对次数筛题；{@code difficulty} 仍是题目 metadata，本策略不看它。</p>
 */
@Service
public class RandomPracticeSelector {
    /** 选出的题、它冻结的 target KnowledgePoint，以及实际走到的 lane。 */
    public record Selection(String questionId, String targetKnowledgePointId, String drawReason,
                            PracticeSelectionStore.RequestedKnowledgePool requestedPool) {
        public boolean consumesPoolRotation() { return requestedPool != null; }
    }

    private final PracticeSelectionStore store;

    public RandomPracticeSelector(PracticeSelectionStore store) { this.store = store; }

    /**
     * run 开始时还能出的不同 Question 数。
     * World 用它把 {@code plannedRounds} 收敛成 {@code min(activity rounds, remainingToday)}，
     * 避免 run 中途为了凑轮数重复出题；为 0 时启动阶段直接给业务提示，不先启动再 500。
     */
    public int remainingToday(String learnerId, Set<String> allowedKnowledgePointIds, Set<String> runSeen) {
        Set<String> questions = new LinkedHashSet<>();
        eligibleByKnowledgePoint(allowedKnowledgePointIds, excludedToday(learnerId, runSeen))
                .values().forEach(questions::addAll);
        return questions.size();
    }

    /**
     * 不考虑当天已出题的前提下，范围内是否还存在正式题候选。
     * 只用于把“今天已经全部出过”和“范围内根本没有正式题”区分成不同业务文案。
     */
    public boolean hasAnyCandidate(Set<String> allowedKnowledgePointIds, Set<String> runSeen) {
        return !eligibleByKnowledgePoint(allowedKnowledgePointIds,
                runSeen == null ? Set.of() : runSeen).isEmpty();
    }

    /**
     * 选出下一道 RANDOM 正式题。
     * 返回空表示当前 Book scope 当天 RANDOM 候选已经全部耗尽，调用方必须明确结束，不得重复当天已出题。
     */
    public Optional<Selection> select(String learnerId, Set<String> allowedKnowledgePointIds, Set<String> runSeen) {
        Instant now = Instant.now();
        boolean firstOfDay = store.randomQuestionIdsBetween(learnerId,
                PracticeBusinessDay.startOfDay(now), PracticeBusinessDay.endOfDay(now)).isEmpty();
        Map<String, List<String>> eligible = eligibleByKnowledgePoint(allowedKnowledgePointIds,
                excludedToday(learnerId, runSeen));
        if (eligible.isEmpty()) return Optional.empty();
        KnowledgePointChoice choice = chooseKnowledgePoint(learnerId, eligible.keySet(), firstOfDay);
        Selection draw = drawInKnowledgePoint(learnerId, choice.knowledgePointId(),
                eligible.get(choice.knowledgePointId()));
        return Optional.of(new Selection(draw.questionId(), draw.targetKnowledgePointId(),
                draw.drawReason(), choice.requestedPool()));
    }

    /**
     * Attempt 已成功写入后，由调用方在同一事务内先记录严格的最近指针，
     * 再按本次 selection 是否属于触发事件决定是否消费池轮换槽。
     */
    public void recordPersistedAttempt(String learnerId, String attemptId, Selection selection) {
        store.recordLastRandomAttempt(learnerId, attemptId);
        recordPoolRotation(learnerId, selection);
    }

    /** 只更新 ALL / WRONG 池槽；供不创建真实 Attempt 的 selector 单元集成测试使用。 */
    public void recordPoolRotation(String learnerId, Selection selection) {
        if (selection != null && selection.consumesPoolRotation()) {
            store.recordRequestedKnowledgePool(learnerId, selection.requestedPool());
        }
    }

    /** 当天已经占用的 RANDOM 额度 + 本 run 额外保护（run 内 seen 不是唯一事实源）。 */
    private Set<String> excludedToday(String learnerId, Set<String> runSeen) {
        Instant now = Instant.now();
        Set<String> excluded = new LinkedHashSet<>(store.randomQuestionIdsBetween(learnerId,
                PracticeBusinessDay.startOfDay(now), PracticeBusinessDay.endOfDay(now)));
        if (runSeen != null) excluded.addAll(runSeen);
        return excluded;
    }

    /** 当前仍有候选题的 KP → 候选题。返回的 key 集合就是 eligible KP 集合。 */
    private Map<String, List<String>> eligibleByKnowledgePoint(Set<String> allowedKnowledgePointIds,
                                                               Set<String> excludedQuestionIds) {
        Map<String, List<String>> eligible = new LinkedHashMap<>();
        for (PracticeSelectionStore.KnowledgeCandidate candidate : store.knowledgeCandidates(allowedKnowledgePointIds)) {
            if (excludedQuestionIds.contains(candidate.questionId())) continue;
            eligible.computeIfAbsent(candidate.knowledgePointId(), key -> new ArrayList<>())
                    .add(candidate.questionId());
        }
        return eligible;
    }

    /**
     * KP transition 规则。只有明确的 grading 结果能驱动 transition：
     *
     * <pre>
     * 当天第一题（今天还没有 RANDOM 发题）        → 消费一次 ALL / WRONG 池轮换
     * 上一题 assessment = correct                → 优先换 KP，并消费一次池轮换
     * 上一题 assessment = wrong / partial        → 优先留在原 target KP
     * 上一题 assessment = null（active / revealed 未自评） → 不推断为错题，重新纯随机
     * </pre>
     *
     * <p>未作答的上一题仍然占用当天 Question quota，但它不是“上一题的 grading 结果”，
     * 因此既不能当成 wrong，也不能拿更早一天 / 更早一题的结果替代。</p>
     */
    private KnowledgePointChoice chooseKnowledgePoint(String learnerId, Set<String> eligible, boolean firstOfDay) {
        if (firstOfDay) return chooseFromRotatingPool(learnerId, eligible);
        PracticeSelectionStore.LastRandomAttempt previous = store.lastRandomAttempt(learnerId).orElse(null);
        if (previous == null) return new KnowledgePointChoice(randomOf(eligible), null);
        String previousPoint = previous.knowledgePointId();
        String assessment = previous.assessment();
        if ("wrong".equals(assessment) || "partial".equals(assessment)) {
            // wrong / partial：优先留在原 target KP；该 KP 当天无题可选时再切换其他 eligible KP。
            if (previousPoint != null && eligible.contains(previousPoint))
                return new KnowledgePointChoice(previousPoint, null);
            return new KnowledgePointChoice(randomOf(switchToOtherPoints(eligible, previousPoint)), null);
        }
        if ("correct".equals(assessment)) {
            // correct：池轮换发生在 KP 层；先排除 previous，再尝试当次池，失败则回退 ALL。
            return chooseFromRotatingPool(learnerId, switchToOtherPoints(eligible, previousPoint));
        }
        // assessment 未知：上一题只是被发出、并未真正判题，不得推断成错题。
        return new KnowledgePointChoice(randomOf(eligible), null);
    }

    private KnowledgePointChoice chooseFromRotatingPool(String learnerId, Set<String> allCandidates) {
        PracticeSelectionStore.RequestedKnowledgePool requested = store.nextRequestedKnowledgePool(learnerId);
        Set<String> candidates = allCandidates;
        if (requested == PracticeSelectionStore.RequestedKnowledgePool.WRONG) {
            Set<String> wrongPoints = store.activeWrongKnowledgePointIds(learnerId, allCandidates);
            if (!wrongPoints.isEmpty()) candidates = wrongPoints;
        }
        return new KnowledgePointChoice(randomOf(candidates), requested);
    }

    private record KnowledgePointChoice(String knowledgePointId,
                                        PracticeSelectionStore.RequestedKnowledgePool requestedPool) {}

    /** 除上一 target KP 之外的 eligible KP；为空时回退整个 eligible 集合，避免死锁。 */
    private static Set<String> switchToOtherPoints(Set<String> eligible, String previousPoint) {
        List<String> others = eligible.stream().filter(id -> !id.equals(previousPoint)).toList();
        return others.isEmpty() ? eligible : new LinkedHashSet<>(others);
    }

    /** 先按 Lane 决定候选，再按 oldest 事实排序。 */
    private Selection drawInKnowledgePoint(String learnerId, String pointId, List<String> candidates) {
        int drawn = store.randomDrawCount(learnerId, pointId);
        if (drawn % 2 == 0) {
            return new Selection(oldestFirst(learnerId, candidates), pointId, PracticeDrawReason.OLDEST, null);
        }
        // wrong lane：只看当前 active 永久错题本。是否“属于当前 KP”已经由候选题集合
        // （question_resource_knowledge）保证，这里只问“该题现在是否仍是 active 错题”。
        Set<String> activeWrong = store.activeWrongQuestionIds(learnerId, candidates);
        List<String> wrongCandidates = candidates.stream().filter(activeWrong::contains).toList();
        if (!wrongCandidates.isEmpty()) {
            return new Selection(oldestFirst(learnerId, wrongCandidates), pointId, PracticeDrawReason.WRONG, null);
        }
        // wrong lane 没有可用错题：fallback oldest，但这个 wrong slot 仍然算消费，下一次回到 oldest。
        return new Selection(oldestFirst(learnerId, candidates), pointId, PracticeDrawReason.WRONG_FALLBACK, null);
    }

    /**
     * oldest lane：从未 graded 过最优先，其次 last graded 最早，同时间用 question_id 稳定兜底。
     * last graded 读取 Learner 全部正式作答模式的历史，不只是 RANDOM。
     */
    private String oldestFirst(String learnerId, List<String> candidates) {
        Map<String, Instant> lastGraded = store.lastGradedAt(learnerId, candidates);
        return candidates.stream()
                .sorted(Comparator
                        .comparing((String questionId) -> lastGraded.get(questionId),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Comparator.naturalOrder()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("RANDOM 候选题不能为空。"));
    }

    private static String randomOf(Collection<String> values) {
        List<String> candidates = List.copyOf(values);
        if (candidates.isEmpty()) throw new IllegalStateException("RANDOM 候选知识点不能为空。");
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
}

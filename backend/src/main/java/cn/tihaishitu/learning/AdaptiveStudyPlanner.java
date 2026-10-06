package cn.tihaishitu.learning;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.game.KnowledgeQuestionPoolStore;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdaptiveStudyPlanner {
    public record AdaptiveStudyPlan(Set<String> allowedKnowledgePointIds,
                                    List<String> targetKnowledgePointIds) {}
    public record QuestionContext(int preferredDifficulty) {}

    private final KnowledgeQuestionPoolStore pool;
    private final LearnerKnowledgeStateStore states;
    private final LearnerKnowledgeStateService stateService;
    private final KnowledgeMasteryModel model = new KnowledgeMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public AdaptiveStudyPlanner(KnowledgeQuestionPoolStore pool, LearnerKnowledgeStateStore states) {
        this(pool, states, null);
    }

    @Autowired
    public AdaptiveStudyPlanner(KnowledgeQuestionPoolStore pool, LearnerKnowledgeStateStore states,
                                LearnerKnowledgeStateService stateService) {
        this.pool = pool;
        this.states = states;
        this.stateService = stateService;
    }

    /**
     * 正式目标随机抽取：候选池是 Selected Books 覆盖到、且至少存在一道正式父题的知识点。
     * 不再使用 dependency readiness、Mastery 带宽或 Review due 决定谁能进入本轮计划。
     */
    public AdaptiveStudyPlan plan(String learnerId, Set<String> selectedBookIds,
                                  List<String> focusedKnowledgePointIds, boolean manualFocus, int count) {
        return planAt(learnerId, selectedBookIds, focusedKnowledgePointIds, manualFocus, count, clock.instant());
    }

    public AdaptiveStudyPlan randomPlan(String learnerId, Set<String> selectedBookIds, int count) {
        return planAt(learnerId, selectedBookIds, List.of(), false, count, clock.instant());
    }

    AdaptiveStudyPlan planAt(String learnerId, Set<String> selectedBookIds,
                             List<String> focusedKnowledgePointIds, boolean manualFocus,
                             int count, Instant now) {
        List<KnowledgePointDto> scope = pool.bookScope(selectedBookIds);
        Set<String> allowed = new LinkedHashSet<>();
        scope.forEach(point -> allowed.add(point.id()));
        Set<String> playable = pool.playableKnowledgePointIds(allowed);

        List<String> candidates = scope.stream().map(KnowledgePointDto::id)
                .filter(playable::contains).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (candidates.size() < count) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "当前学习范围只有 " + candidates.size() + " 个可挑战知识点，本活动需要 "
                            + count + " 个不同知识点。");
        }

        Collections.shuffle(candidates);
        Set<String> focused = manualFocus ? new LinkedHashSet<>(focusedKnowledgePointIds) : Set.of();
        Map<String, Integer> focusOrder = new HashMap<>();
        for (int index = 0; index < focusedKnowledgePointIds.size(); index++)
            focusOrder.putIfAbsent(focusedKnowledgePointIds.get(index), index);
        candidates.sort(Comparator
                .comparingInt((String id) -> focused.contains(id) ? 0 : 1)
                .thenComparingInt(id -> focusOrder.getOrDefault(id, Integer.MAX_VALUE)));
        return new AdaptiveStudyPlan(Collections.unmodifiableSet(allowed),
                List.copyOf(candidates.subList(0, count)));
    }

    /**
     * 目标确定后的出题上下文。只保留难度软提示：难度不阻止任何正式题被抽中。
     */
    public QuestionContext questionContext(String learnerId, Set<String> frozenAllowedKnowledgePointIds,
                                           String targetKnowledgePointId, String profileDifficulty) {
        return questionContextAt(learnerId, frozenAllowedKnowledgePointIds, targetKnowledgePointId,
                profileDifficulty, clock.instant());
    }

    QuestionContext questionContextAt(String learnerId, Set<String> frozenAllowedKnowledgePointIds,
                                      String targetKnowledgePointId, String profileDifficulty, Instant now) {
        Set<String> allowed = new LinkedHashSet<>(frozenAllowedKnowledgePointIds);
        Map<String, KnowledgeMasteryModel.State> stateByPoint = stateMap(learnerId, allowed, now);
        Map<String, Double> effectiveByPoint = effectiveMap(stateByPoint, now);
        KnowledgeMasteryModel.State target = stateByPoint.get(targetKnowledgePointId);
        int preferred = AdaptiveSchedulingPolicy.preferredDifficulty(target,
                effectiveByPoint.getOrDefault(targetKnowledgePointId, 0d), profileDifficulty);
        return new QuestionContext(preferred);
    }

    private Map<String, KnowledgeMasteryModel.State> stateMap(String learnerId, Set<String> pointIds, Instant now) {
        if (stateService != null) return stateService.settledStates(learnerId, pointIds, now);
        Map<String, KnowledgeMasteryModel.State> result = new LinkedHashMap<>();
        states.findForKnowledgePoints(learnerId, pointIds)
                .forEach(row -> result.put(row.knowledgePointId(), row.state()));
        return result;
    }

    private Map<String, Double> effectiveMap(Map<String, KnowledgeMasteryModel.State> stateByPoint, Instant now) {
        Map<String, Double> result = new HashMap<>();
        stateByPoint.forEach((id, state) -> result.put(id, model.effectiveMastery(state, now)));
        return result;
    }
}

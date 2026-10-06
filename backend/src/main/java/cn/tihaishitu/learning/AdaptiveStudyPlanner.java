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

import static cn.tihaishitu.learning.KnowledgeModelPolicy.READY_THRESHOLD;

@Service
public class AdaptiveStudyPlanner {
    public record AdaptiveStudyPlan(Set<String> allowedKnowledgePointIds,
                                    Set<String> readyKnowledgePointIds,
                                    List<String> targetKnowledgePointIds) {}
    public record QuestionContext(Set<String> readyKnowledgePointIds, int preferredDifficulty) {}

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

    public AdaptiveStudyPlan plan(String learnerId, Set<String> selectedBookIds,
                                  List<String> focusedKnowledgePointIds, boolean manualFocus, int count) {
        return planAt(learnerId, selectedBookIds, focusedKnowledgePointIds, manualFocus, count, clock.instant());
    }

    public AdaptiveStudyPlan randomPlan(String learnerId, Set<String> selectedBookIds, int count) {
        List<KnowledgePointDto> scope = pool.bookScope(selectedBookIds);
        Set<String> allowed = new LinkedHashSet<>();
        scope.forEach(point -> allowed.add(point.id()));
        Map<String, KnowledgeMasteryModel.State> stateByPoint = stateMap(learnerId, allowed, clock.instant());
        Set<String> ready = readySet(allowed, effectiveMap(stateByPoint, clock.instant()));
        Set<String> playable = pool.adaptivePlayableKnowledgePointIds(allowed, ready);
        List<String> candidates = scope.stream().map(KnowledgePointDto::id)
                .filter(playable::contains).distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (candidates.size() < count) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "当前学习范围只有 " + candidates.size() + " 个可挑战知识点，本活动需要 "
                            + count + " 个不同知识点。");
        }
        Collections.shuffle(candidates);
        return new AdaptiveStudyPlan(Collections.unmodifiableSet(allowed), Collections.unmodifiableSet(ready),
                List.copyOf(candidates.subList(0, count)));
    }

    AdaptiveStudyPlan planAt(String learnerId, Set<String> selectedBookIds,
                             List<String> focusedKnowledgePointIds, boolean manualFocus,
                             int count, Instant now) {
        List<KnowledgePointDto> scope = pool.bookScope(selectedBookIds);
        Set<String> allowed = new LinkedHashSet<>();
        scope.forEach(point -> allowed.add(point.id()));
        Map<String, KnowledgeMasteryModel.State> stateByPoint = stateMap(learnerId, allowed, now);
        Map<String, Double> effectiveByPoint = effectiveMap(stateByPoint, now);
        Set<String> ready = readySet(allowed, effectiveByPoint);
        Set<String> playable = pool.adaptivePlayableKnowledgePointIds(allowed, ready);

        List<String> candidates = scope.stream().map(KnowledgePointDto::id)
                .filter(playable::contains).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (candidates.size() < count) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "当前学习状态下只有 " + candidates.size() + " 个可训练知识点，部分综合题的前置知识尚未达到基本掌握；"
                            + "本活动需要 " + count + " 个不同知识点。请先巩固前置知识或调整学习范围。");
        }

        Collections.shuffle(candidates);
        Set<String> focused = manualFocus ? new LinkedHashSet<>(focusedKnowledgePointIds) : Set.of();
        Map<String, Integer> focusOrder = new HashMap<>();
        for (int index = 0; index < focusedKnowledgePointIds.size(); index++)
            focusOrder.putIfAbsent(focusedKnowledgePointIds.get(index), index);
        Comparator<String> priority = Comparator
                .comparingInt((String id) -> focused.contains(id) ? 0 : 1)
                .thenComparingInt(id -> AdaptiveSchedulingPolicy.targetPriority(
                        stateByPoint.get(id), effectiveByPoint.getOrDefault(id, 0d),
                        ReviewSchedulingPolicy.dueWithin24Hours(stateByPoint.get(id), now)))
                .thenComparingDouble(id -> effectiveByPoint.getOrDefault(id, 0d))
                .thenComparing(id -> lastEvidence(stateByPoint.get(id)), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(id -> focusOrder.getOrDefault(id, Integer.MAX_VALUE));
        candidates.sort(priority);
        return new AdaptiveStudyPlan(Collections.unmodifiableSet(allowed), Collections.unmodifiableSet(ready),
                List.copyOf(candidates.subList(0, count)));
    }

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
        Set<String> ready = readySet(allowed, effectiveByPoint);
        KnowledgeMasteryModel.State target = stateByPoint.get(targetKnowledgePointId);
        int preferred = AdaptiveSchedulingPolicy.preferredDifficulty(target,
                effectiveByPoint.getOrDefault(targetKnowledgePointId, 0d), profileDifficulty);
        return new QuestionContext(Collections.unmodifiableSet(ready), preferred);
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

    private static Set<String> readySet(Set<String> allowed, Map<String, Double> effectiveByPoint) {
        Set<String> result = new LinkedHashSet<>();
        allowed.stream().filter(id -> effectiveByPoint.getOrDefault(id, 0d) >= READY_THRESHOLD)
                .forEach(result::add);
        return result;
    }

    private static Instant lastEvidence(KnowledgeMasteryModel.State state) {
        return state == null ? null : state.lastEvidenceAt();
    }
}

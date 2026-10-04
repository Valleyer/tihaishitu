package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class KnowledgeQuestionPoolService {
    public enum Mode { NORMAL, TRAINING }

    public record QuestionPoolRequest(
            String currentKnowledgePointId,
            Set<String> allowedKnowledgePointIds,
            Set<String> seenQuestionIds,
            Integer preferredDifficulty,
            Mode mode) {
        public QuestionPoolRequest {
            allowedKnowledgePointIds = allowedKnowledgePointIds == null
                    ? Set.of() : Set.copyOf(allowedKnowledgePointIds);
            seenQuestionIds = seenQuestionIds == null ? Set.of() : Set.copyOf(seenQuestionIds);
            mode = mode == null ? Mode.NORMAL : mode;
        }
    }

    public record AdaptiveQuestionPoolRequest(
            String currentKnowledgePointId,
            Set<String> allowedKnowledgePointIds,
            Set<String> readyKnowledgePointIds,
            Set<String> seenQuestionIds,
            int preferredDifficulty,
            Mode mode) {
        public AdaptiveQuestionPoolRequest {
            allowedKnowledgePointIds = allowedKnowledgePointIds == null
                    ? Set.of() : Set.copyOf(allowedKnowledgePointIds);
            readyKnowledgePointIds = readyKnowledgePointIds == null
                    ? Set.of() : Set.copyOf(readyKnowledgePointIds);
            seenQuestionIds = seenQuestionIds == null ? Set.of() : Set.copyOf(seenQuestionIds);
            mode = mode == null ? Mode.NORMAL : mode;
        }
    }

    public record StudyPlan(Set<String> allowedKnowledgePointIds, List<String> knowledgePointIds) {}

    private final KnowledgeQuestionPoolStore store;
    private final LearnerQuestionExposureStore exposures;

    public KnowledgeQuestionPoolService(KnowledgeQuestionPoolStore store,
                                        LearnerQuestionExposureStore exposures) {
        this.store = store;
        this.exposures = exposures;
    }

    public StudyPlan planKnowledgePoints(Set<String> selectedBookIds, int count) {
        return planKnowledgePoints(selectedBookIds, List.of(), count);
    }

    public StudyPlan planKnowledgePoints(Set<String> selectedBookIds, List<String> focusedKnowledgePointIds, int count) {
        List<KnowledgePointDto> scope = store.bookScope(selectedBookIds);
        Set<String> allowed = new LinkedHashSet<>();
        scope.forEach(point -> allowed.add(point.id()));
        Set<String> playable = store.playableKnowledgePointIds(allowed);
        List<String> planned = scope.stream().map(KnowledgePointDto::id)
                .filter(playable::contains).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (planned.size() < count) {
            throw bad("当前文集只有 " + planned.size() + " 个可用知识点，本活动需要 " + count + " 个。");
        }
        Collections.shuffle(planned);
        if (focusedKnowledgePointIds != null && !focusedKnowledgePointIds.isEmpty()) {
            Set<String> requested = new LinkedHashSet<>(focusedKnowledgePointIds);
            List<String> focused = focusedKnowledgePointIds.stream().filter(playable::contains).distinct().toList();
            planned.removeIf(requested::contains);
            List<String> prioritized = new ArrayList<>(focused);
            prioritized.addAll(planned);
            planned = prioritized;
        }
        return new StudyPlan(Collections.unmodifiableSet(allowed),
                List.copyOf(planned.subList(0, count)));
    }

    public Set<String> allowedKnowledgePointIds(Set<String> selectedBookIds) {
        Set<String> allowed = new LinkedHashSet<>();
        store.bookScope(selectedBookIds).forEach(point -> allowed.add(point.id()));
        return Collections.unmodifiableSet(allowed);
    }

    public List<QuestionDto> eligibleQuestions(QuestionPoolRequest request) {
        if (request.currentKnowledgePointId() == null || request.currentKnowledgePointId().isBlank()) {
            throw bad("当前修习知识点不能为空。");
        }
        return store.candidatesForCore(
                        request.currentKnowledgePointId(), request.allowedKnowledgePointIds()).stream()
                .filter(question -> !request.seenQuestionIds().contains(question.id()))
                .toList();
    }

    public QuestionDto selectQuestion(QuestionPoolRequest request) {
        List<QuestionDto> candidates = eligibleQuestions(request);
        if (candidates.isEmpty()) {
            throw bad("该知识点当前可用题目已用尽，请结束或退出本轮训练。");
        }
        if (request.mode() == Mode.NORMAL) return random(candidates);

        List<QuestionDto> simple = candidates.stream().filter(question -> question.difficulty() <= 2).toList();
        if (!simple.isEmpty()) return random(simple);
        int minimum = candidates.stream().mapToInt(QuestionDto::difficulty).min().orElseThrow();
        return random(candidates.stream().filter(question -> question.difficulty() == minimum).toList());
    }

    public List<QuestionDto> eligibleQuestionsForLearner(AdaptiveQuestionPoolRequest request) {
        if (request.currentKnowledgePointId() == null || request.currentKnowledgePointId().isBlank()) {
            throw bad("当前修习知识点不能为空。");
        }
        return store.adaptiveCandidatesForCore(request.currentKnowledgePointId(),
                        request.allowedKnowledgePointIds(), request.readyKnowledgePointIds()).stream()
                .filter(question -> !request.seenQuestionIds().contains(question.id()))
                .toList();
    }

    public QuestionDto selectQuestionForLearner(AdaptiveQuestionPoolRequest request) {
        return random(selectedDifficultyBucket(request));
    }

    public QuestionDto selectQuestionForLearner(String learnerId, AdaptiveQuestionPoolRequest request) {
        List<QuestionDto> selectedDifficulty = selectedDifficultyBucket(request);
        Map<String, LearnerQuestionExposureStore.Exposure> history = exposures.findForQuestions(
                learnerId, selectedDifficulty.stream().map(QuestionDto::id).toList());
        return rotate(selectedDifficulty, history);
    }

    private List<QuestionDto> selectedDifficultyBucket(AdaptiveQuestionPoolRequest request) {
        List<QuestionDto> candidates = eligibleQuestionsForLearner(request);
        if (candidates.isEmpty()) {
            throw bad("该知识点当前可用题目已用尽，或前置知识尚未达到基本掌握。请结束或退出本轮训练。");
        }
        if (request.mode() == Mode.NORMAL) {
            return nearestDifficultyBucket(candidates, request.preferredDifficulty());
        }
        int remedialPreferred = Math.min(2, request.preferredDifficulty());
        List<QuestionDto> remedial = candidates.stream().filter(question -> question.difficulty() <= 2).toList();
        if (!remedial.isEmpty()) return nearestDifficultyBucket(remedial, remedialPreferred);
        int minimum = candidates.stream().mapToInt(QuestionDto::difficulty).min().orElseThrow();
        return candidates.stream().filter(question -> question.difficulty() == minimum).toList();
    }

    public List<KnowledgePointDto> knowledgeDetails(Collection<String> knowledgePointIds) {
        return store.knowledgeDetails(new LinkedHashSet<>(knowledgePointIds));
    }

    private static QuestionDto random(List<QuestionDto> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static List<QuestionDto> nearestDifficultyBucket(List<QuestionDto> candidates,
                                                              int preferredDifficulty) {
        int selectedDifficulty = candidates.stream().mapToInt(QuestionDto::difficulty).boxed()
                .min(Comparator.comparingInt((Integer difficulty) ->
                                Math.abs(difficulty - preferredDifficulty))
                        .thenComparingInt(Integer::intValue))
                .orElseThrow();
        return candidates.stream().filter(question -> question.difficulty() == selectedDifficulty).toList();
    }

    private static QuestionDto rotate(List<QuestionDto> candidates,
                                      Map<String, LearnerQuestionExposureStore.Exposure> history) {
        List<QuestionDto> neverExposed = candidates.stream()
                .filter(question -> !history.containsKey(question.id())).toList();
        if (!neverExposed.isEmpty()) return random(neverExposed);

        Comparator<QuestionDto> rotationOrder = Comparator
                .comparing((QuestionDto question) -> history.get(question.id()).lastExposedAt())
                .thenComparingInt(question -> history.get(question.id()).exposureCount());
        QuestionDto first = candidates.stream().min(rotationOrder).orElseThrow();
        var firstExposure = history.get(first.id());
        List<QuestionDto> tied = candidates.stream().filter(question -> {
            var exposure = history.get(question.id());
            return exposure.lastExposedAt().equals(firstExposure.lastExposedAt())
                    && exposure.exposureCount() == firstExposure.exposureCount();
        }).toList();
        return random(tied);
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}

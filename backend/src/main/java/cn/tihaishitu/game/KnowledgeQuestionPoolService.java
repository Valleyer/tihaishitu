package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.learning.LearnerKnowledgeStateStore;
import cn.tihaishitu.learning.LearnerQuestionProgressStore;
import cn.tihaishitu.learning.ReviewSchedulingPolicy;
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
import java.time.Clock;

@Service
public class KnowledgeQuestionPoolService {
    public enum Mode { NORMAL, TRAINING }
    public enum DependencyPolicy { REQUIRE_READY, SCOPE_ONLY }

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
            Mode mode,
            DependencyPolicy dependencyPolicy) {
        public AdaptiveQuestionPoolRequest(
                String currentKnowledgePointId,
                Set<String> allowedKnowledgePointIds,
                Set<String> readyKnowledgePointIds,
                Set<String> seenQuestionIds,
                int preferredDifficulty,
                Mode mode) {
            this(currentKnowledgePointId, allowedKnowledgePointIds, readyKnowledgePointIds,
                    seenQuestionIds, preferredDifficulty, mode, DependencyPolicy.REQUIRE_READY);
        }

        public AdaptiveQuestionPoolRequest {
            allowedKnowledgePointIds = allowedKnowledgePointIds == null
                    ? Set.of() : Set.copyOf(allowedKnowledgePointIds);
            readyKnowledgePointIds = readyKnowledgePointIds == null
                    ? Set.of() : Set.copyOf(readyKnowledgePointIds);
            seenQuestionIds = seenQuestionIds == null ? Set.of() : Set.copyOf(seenQuestionIds);
            mode = mode == null ? Mode.NORMAL : mode;
            dependencyPolicy = dependencyPolicy == null
                    ? DependencyPolicy.REQUIRE_READY : dependencyPolicy;
        }
    }

    public record StudyPlan(Set<String> allowedKnowledgePointIds, List<String> knowledgePointIds) {}

    private final KnowledgeQuestionPoolStore store;
    private final LearnerQuestionProgressStore progress;
    private final LearnerKnowledgeStateStore states;
    private final Clock clock = Clock.systemUTC();

    public KnowledgeQuestionPoolService(KnowledgeQuestionPoolStore store,
                                        LearnerQuestionProgressStore progress,
                                        LearnerKnowledgeStateStore states) {
        this.store = store;
        this.progress = progress;
        this.states = states;
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
        List<QuestionDto> candidates = request.dependencyPolicy() == DependencyPolicy.SCOPE_ONLY
                ? request.allowedKnowledgePointIds().contains(request.currentKnowledgePointId())
                    ? store.candidatesForCore(request.currentKnowledgePointId(), request.allowedKnowledgePointIds())
                    : List.of()
                : store.adaptiveCandidatesForCore(request.currentKnowledgePointId(),
                        request.allowedKnowledgePointIds(), request.readyKnowledgePointIds());
        return candidates.stream()
                .filter(question -> !request.seenQuestionIds().contains(question.id()))
                .toList();
    }

    public QuestionDto selectQuestionForLearner(AdaptiveQuestionPoolRequest request) {
        return random(softCandidates(request));
    }

    public QuestionDto selectQuestionForLearner(String learnerId, AdaptiveQuestionPoolRequest request) {
        List<QuestionDto> candidates = softCandidates(request);
        Map<String, LearnerQuestionProgressStore.QuestionProgress> history = progress.latestGradedForQuestions(
                learnerId, request.currentKnowledgePointId(), candidates.stream().map(QuestionDto::id).toList());
        return softSelect(candidates, history, request.preferredDifficulty());
    }

    public List<QuestionDto> eligibleKnowledgeDrillQuestions(String learnerId, AdaptiveQuestionPoolRequest request) {
        List<QuestionDto> candidates = eligibleQuestionsForLearner(request);
        if (candidates.isEmpty()) return List.of();
        Map<String, LearnerQuestionProgressStore.QuestionProgress> history = progress.latestGradedForQuestions(
                learnerId, request.currentKnowledgePointId(), candidates.stream().map(QuestionDto::id).toList());
        boolean due = states.find(learnerId, request.currentKnowledgePointId())
                .map(state -> ReviewSchedulingPolicy.dueWithin24Hours(state, clock.instant())).orElse(false);
        return candidates.stream().filter(question -> {
            var item = history.get(question.id());
            return item == null || !item.graded() || !"correct".equals(item.assessment()) || due;
        }).toList();
    }

    public QuestionDto selectKnowledgeDrillQuestion(String learnerId, AdaptiveQuestionPoolRequest request) {
        List<QuestionDto> candidates = eligibleKnowledgeDrillQuestions(learnerId, request);
        if (candidates.isEmpty()) throw bad("这个知识点当前没有待练的新题，已掌握题目会在复习到期后重新开放。");
        Map<String, LearnerQuestionProgressStore.QuestionProgress> history = progress.latestGradedForQuestions(
                learnerId, request.currentKnowledgePointId(), candidates.stream().map(QuestionDto::id).toList());
        int bestTier = candidates.stream().mapToInt(question -> tier(history.get(question.id()))).min().orElseThrow();
        return softSelect(candidates.stream().filter(question -> tier(history.get(question.id())) == bestTier).toList(),
                history, request.preferredDifficulty());
    }

    private List<QuestionDto> softCandidates(AdaptiveQuestionPoolRequest request) {
        List<QuestionDto> candidates = eligibleQuestionsForLearner(request);
        if (candidates.isEmpty()) {
            if (request.dependencyPolicy() == DependencyPolicy.SCOPE_ONLY) {
                throw bad("当前知识点暂无可用于专项练习的正式题。");
            }
            throw bad("该知识点当前可用题目已用尽，或前置知识尚未达到基本掌握。请结束或退出本轮训练。");
        }
        if (request.mode() == Mode.NORMAL) return candidates;
        List<QuestionDto> remedial = candidates.stream().filter(question -> question.difficulty() <= 2).toList();
        if (!remedial.isEmpty()) return remedial;
        int minimum = candidates.stream().mapToInt(QuestionDto::difficulty).min().orElseThrow();
        return candidates.stream().filter(question -> question.difficulty() == minimum).toList();
    }

    public List<KnowledgePointDto> knowledgeDetails(Collection<String> knowledgePointIds) {
        return store.knowledgeDetails(new LinkedHashSet<>(knowledgePointIds));
    }

    private static QuestionDto random(List<QuestionDto> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static QuestionDto softSelect(List<QuestionDto> candidates,
            Map<String, LearnerQuestionProgressStore.QuestionProgress> history, int preferredDifficulty) {
        Comparator<QuestionDto> rotationOrder = Comparator
                .comparingInt((QuestionDto question) -> exposureCount(history.get(question.id())))
                .thenComparingInt(question -> Math.abs(question.difficulty() - preferredDifficulty))
                .thenComparing(question -> lastExposedAt(history.get(question.id())),
                        Comparator.nullsFirst(Comparator.naturalOrder()));
        QuestionDto first = candidates.stream().min(rotationOrder).orElseThrow();
        List<QuestionDto> tied = candidates.stream().filter(question -> {
            var left = history.get(question.id()); var right = history.get(first.id());
            return exposureCount(left) == exposureCount(right)
                    && Math.abs(question.difficulty() - preferredDifficulty)
                    == Math.abs(first.difficulty() - preferredDifficulty)
                    && java.util.Objects.equals(lastExposedAt(left), lastExposedAt(right));
        }).toList();
        return random(tied);
    }

    private static int tier(LearnerQuestionProgressStore.QuestionProgress progress) {
        if (progress == null || !progress.graded()) return 0;
        return "correct".equals(progress.assessment()) ? 2 : 1;
    }
    private static int exposureCount(LearnerQuestionProgressStore.QuestionProgress progress) {
        return progress == null ? 0 : progress.exposureCount();
    }
    private static java.time.Instant lastExposedAt(LearnerQuestionProgressStore.QuestionProgress progress) {
        return progress == null ? null : progress.lastExposedAt();
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}

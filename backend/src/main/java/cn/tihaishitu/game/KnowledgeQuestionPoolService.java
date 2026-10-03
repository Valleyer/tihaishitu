package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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

    public record StudyPlan(Set<String> allowedKnowledgePointIds, List<String> knowledgePointIds) {}

    private final KnowledgeQuestionPoolStore store;

    public KnowledgeQuestionPoolService(KnowledgeQuestionPoolStore store) {
        this.store = store;
    }

    public StudyPlan planKnowledgePoints(Set<String> selectedBookIds, int count) {
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

    public List<KnowledgePointDto> knowledgeDetails(Collection<String> knowledgePointIds) {
        return store.knowledgeDetails(new LinkedHashSet<>(knowledgePointIds));
    }

    private static QuestionDto random(List<QuestionDto> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}

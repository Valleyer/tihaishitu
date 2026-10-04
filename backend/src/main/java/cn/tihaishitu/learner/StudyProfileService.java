package cn.tihaishitu.learner;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.game.KnowledgeQuestionPoolStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class StudyProfileService {
    private final StudyProfileStore store;
    private final KnowledgeQuestionPoolStore poolStore;
    private final KnowledgeQuestionPoolService pool;
    private final LearnerStore learners;

    public StudyProfileService(StudyProfileStore store, KnowledgeQuestionPoolStore poolStore,
                               KnowledgeQuestionPoolService pool, LearnerStore learners) {
        this.store = store;
        this.poolStore = poolStore;
        this.pool = pool;
        this.learners = learners;
    }

    public StudyProfileResponse current() { return response(store.find(LearnerContext.learnerId())); }
    public StudyProfileStore.Profile rawCurrent() { return store.find(LearnerContext.learnerId()); }

    @Transactional
    public StudyProfileResponse update(UpdateStudyProfileRequest request) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        StudyProfileStore.Profile current = store.find(learnerId);
        if (request.expectedRevision() != null && request.expectedRevision() != current.revision())
            throw new ApiException(HttpStatus.CONFLICT, "学习设置已在其他页面更新，请刷新后重试。");
        long revision = request.expectedRevision() == null ? current.revision() : request.expectedRevision();
        String pace = allowed(request.pace(), Set.of("slow", "normal"), "学习节奏");
        String difficulty = allowed(request.difficulty(), Set.of("gentle", "standard"), "题目难度");
        String mode = allowed(request.focusMode(), Set.of("auto", "manual"), "专注模式");
        List<String> books = distinct(request.selectedBookIds());
        if (books.isEmpty()) throw bad("至少选择一本文集作为学习范围。");
        List<String> enabled = store.enabledBookIds(new LinkedHashSet<>(books));
        if (enabled.size() != books.size()) throw bad("所选文集中包含不存在或已停用的文集。");
        Set<String> scope = new LinkedHashSet<>();
        poolStore.bookScope(new LinkedHashSet<>(books)).forEach(point -> scope.add(point.id()));
        List<String> focus = distinct(request.focusedKnowledgePointIds());
        Set<String> invalid = new LinkedHashSet<>(focus);
        invalid.removeAll(scope);
        if (!current.focusedKnowledgePointIds().containsAll(invalid))
            throw bad("重点知识点必须处于所选文集的有效知识范围内。");
        focus = focus.stream().filter(scope::contains).toList();
        if (store.updateBase(learnerId, revision, pace, difficulty, mode) == 0)
            throw new ApiException(HttpStatus.CONFLICT, "学习设置已在其他页面更新，请刷新后重试。");
        Map<String, Integer> weights = new LinkedHashMap<>();
        books.forEach(id -> {
            int weight = request.weights() == null ? current.weights().getOrDefault(id, 100)
                    : request.weights().getOrDefault(id, current.weights().getOrDefault(id, 100));
            if (weight < 1 || weight > 1000) throw bad("文集权重须在 1–1000 之间。");
            weights.put(id, weight);
        });
        store.replaceBooks(learnerId, books, weights);
        store.replaceFocus(learnerId, focus);
        return response(store.find(learnerId));
    }

    public KnowledgeQuestionPoolService.StudyPlan plan(int count) {
        StudyProfileStore.Profile profile = rawCurrent();
        return pool.planKnowledgePoints(new LinkedHashSet<>(profile.selectedBookIds()),
                "manual".equals(profile.focusMode()) ? profile.focusedKnowledgePointIds() : List.of(), count);
    }

    private StudyProfileResponse response(StudyProfileStore.Profile profile) {
        List<KnowledgePointDto> details = pool.knowledgeDetails(profile.focusedKnowledgePointIds());
        return new StudyProfileResponse(profile.pace(), profile.difficulty(), profile.focusMode(), profile.revision(),
                profile.selectedBookIds(), profile.weights(), profile.focusedKnowledgePointIds(), details);
    }

    private static String allowed(String value, Set<String> allowed, String label) {
        if (value == null || !allowed.contains(value)) throw bad(label + "不合法。");
        return value;
    }
    private static List<String> distinct(List<String> values) {
        if (values == null) return List.of();
        return List.copyOf(new LinkedHashSet<>(values.stream().filter(Objects::nonNull).filter(v -> !v.isBlank()).toList()));
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }

    public record UpdateStudyProfileRequest(String pace, String difficulty, String focusMode,
                                            List<String> selectedBookIds, List<String> focusedKnowledgePointIds,
                                            Map<String, Integer> weights, Long expectedRevision) {}
    public record StudyProfileResponse(String pace, String difficulty, String focusMode, long revision,
                                       List<String> selectedBookIds, Map<String, Integer> weights,
                                       List<String> focusedKnowledgePointIds,
                                       List<KnowledgePointDto> focusedKnowledgePoints) {}
}

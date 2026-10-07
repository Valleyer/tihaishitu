package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.learning.KnowledgePointTag;
import cn.tihaishitu.learning.LearnerQuestionProgressStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

    /**
     * 正式题候选请求。{@code preferredDifficulty} 只作为补救 / training 选题的软提示，
     * 不参与“能不能被抽到”的判定；正式题候选一律在池内随机。
     */
    public record AdaptiveQuestionPoolRequest(
            String currentKnowledgePointId,
            Set<String> allowedKnowledgePointIds,
            Set<String> seenQuestionIds,
            int preferredDifficulty,
            Mode mode) {
        public AdaptiveQuestionPoolRequest {
            allowedKnowledgePointIds = allowedKnowledgePointIds == null
                    ? Set.of() : Set.copyOf(allowedKnowledgePointIds);
            seenQuestionIds = seenQuestionIds == null ? Set.of() : Set.copyOf(seenQuestionIds);
            mode = mode == null ? Mode.NORMAL : mode;
        }
    }

    public record StudyPlan(Set<String> allowedKnowledgePointIds, List<String> knowledgePointIds) {}

    private final KnowledgeQuestionPoolStore store;
    private final LearnerQuestionProgressStore progress;

    public KnowledgeQuestionPoolService(KnowledgeQuestionPoolStore store,
                                        LearnerQuestionProgressStore progress) {
        this.store = store;
        this.progress = progress;
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
        return store.candidatesForKnowledge(
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
        // 正式题候选只看“与当前知识点的关系 + 上下文范围 + published 正式父题”，
        // 不再使用 dependency readiness / Mastery / Review due / preferred difficulty 决定能不能抽到。
        List<QuestionDto> candidates = request.allowedKnowledgePointIds().contains(request.currentKnowledgePointId())
                ? store.candidatesForKnowledge(request.currentKnowledgePointId(), request.allowedKnowledgePointIds())
                : List.of();
        return candidates.stream()
                .filter(question -> !request.seenQuestionIds().contains(question.id()))
                .toList();
    }

    /**
     * 正式题抽取：候选池内随机。
     * Session 内通过 seenQuestionIds 排除，避免立刻重复；新 Session 重新进入随机池。
     */
    public QuestionDto selectQuestionForLearner(AdaptiveQuestionPoolRequest request) {
        return random(softCandidates(request));
    }

    public QuestionDto selectQuestionForLearner(String learnerId, AdaptiveQuestionPoolRequest request) {
        return selectKnowledgeDrillQuestion(learnerId, request);
    }

    /**
     * Knowledge 专项候选同样只受“关系 + 范围 + published + Session 内未见”限制。
     * core 与 auxiliary 都算候选；今天已经答对、Review 未到期、其他知识点未掌握都不再让候选清空。
     */
    public List<QuestionDto> eligibleKnowledgeDrillQuestions(String learnerId, AdaptiveQuestionPoolRequest request) {
        return eligibleQuestionsForLearner(request);
    }

    /**
     * 专项出题：候选池内等概率随机。
     *
     * <p>长期规则：正式题抽取不再使用 dependency readiness、Mastery、Review due、
     * preferred difficulty 或 exposure 软排序来决定谁能被抽到。seenQuestionIds 由调用方
     * 作为硬排除传入，新 Session 重新进入随机池。</p>
     */
    public QuestionDto selectKnowledgeDrillQuestion(String learnerId, AdaptiveQuestionPoolRequest request) {
        return random(softCandidates(request));
    }

    private List<QuestionDto> softCandidates(AdaptiveQuestionPoolRequest request) {
        List<QuestionDto> candidates = eligibleQuestionsForLearner(request);
        if (candidates.isEmpty()) throw bad("当前知识点暂无可用于专项练习的正式题。");
        if (request.mode() == Mode.NORMAL) return candidates;
        // Remedial / training 不是普通 Formal Question 抽取，仍保留低难度优先策略。
        List<QuestionDto> remedial = candidates.stream().filter(question -> question.difficulty() <= 2).toList();
        if (!remedial.isEmpty()) return remedial;
        int minimum = candidates.stream().mapToInt(QuestionDto::difficulty).min().orElseThrow();
        return candidates.stream().filter(question -> question.difficulty() == minimum).toList();
    }

    public List<KnowledgePointDto> knowledgeDetails(Collection<String> knowledgePointIds) {
        return store.knowledgeDetails(new LinkedHashSet<>(knowledgePointIds));
    }

    /**
     * Book-level 正式题池（World / 副本 / Book 自由训练使用）。
     *
     * <p>先把 Selected Book(s) 覆盖到的全部 Formal Question 按 question_id 去重收集成一个池子，
     * 排除本 run 已见题，再由调用方等概率随机抽题。**不先选 KnowledgePoint**，
     * 因此各知识点题量不均时不会扭曲抽中概率；知识点数量也不会限制副本轮数。</p>
     */
    public List<QuestionDto> candidatesForBooks(Set<String> selectedBookIds, Set<String> excludedQuestionIds) {
        return store.candidatesForBooks(selectedBookIds, excludedQuestionIds);
    }

    /** Book-level 抽题：候选池内等概率随机。 */
    public QuestionDto selectBookQuestion(Set<String> selectedBookIds, Set<String> excludedQuestionIds) {
        List<QuestionDto> candidates = candidatesForBooks(selectedBookIds, excludedQuestionIds);
        if (candidates.isEmpty()) throw bad("当前学习范围内没有可用的正式题。");
        return random(candidates);
    }

    /** 按题目 ID 直接取回可作为正式题的 Question 列表。 */
    public List<QuestionDto> questionsByIds(Set<String> questionIds) {
        return store.candidatesForQuestions(questionIds);
    }

    /**
     * 一道题的稳定 target KnowledgePoint：优先 scope 内 core（再按 sort_order / id），
     * 否则 scope 内 auxiliary；scope 内找不到时退回该题任意 active 关联知识点。
     */
    public java.util.Optional<String> targetKnowledgePointFor(String questionId,
                                                              Set<String> scopeKnowledgePointIds) {
        return store.targetKnowledgePointFor(questionId, scopeKnowledgePointIds);
    }

    /** 题目的来源元数据（科目 / 来源名 / 年份 / 题号）。 */
    public java.util.Optional<KnowledgeQuestionPoolStore.QuestionSource> questionSource(String questionId) {
        return store.questionSource(questionId);
    }

    /** 题目的全部知识点标签（core / auxiliary 都返回，role 只表达主次）。 */
    public List<KnowledgePointTag> questionKnowledgeTags(String questionId) {
        return store.questionKnowledge(questionId).stream()
                .map(tag -> new KnowledgePointTag(tag.knowledgePointId(), tag.name(), tag.role()))
                .toList();
    }

    /**
     * 按题目 ID 直接取回可作为正式题的 Question（例如 Book-level 答错后的补救重做同一题）。
     * 只校验它仍是 published 正式父题。
     */
    public java.util.Optional<QuestionDto> questionForLearner(String questionId) {
        if (questionId == null) return java.util.Optional.empty();
        return questionsByIds(Set.of(questionId)).stream().findFirst();
    }

    /**
     * 按题目 ID 取回可直接发题的正式题；不属于当前知识范围或已下架时返回空。
     * wrong_review / wrong_drill 用它做可练性校验，避免“点进去才报错”。
     */
    public java.util.Optional<QuestionDto> questionForLearner(String targetKnowledgePointId,
                                                              Set<String> allowedKnowledgePointIds,
                                                              String questionId) {
        if (questionId == null) return java.util.Optional.empty();
        var request = new AdaptiveQuestionPoolRequest(targetKnowledgePointId, allowedKnowledgePointIds,
                Set.of(), 3, Mode.NORMAL);
        return eligibleQuestionsForLearner(request).stream()
                .filter(question -> question.id().equals(questionId)).findFirst();
    }

    private static QuestionDto random(List<QuestionDto> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static int tier(LearnerQuestionProgressStore.QuestionProgress progress) {
        if (progress == null || !progress.graded()) return 0;
        return "correct".equals(progress.assessment()) ? 2 : 1;
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}

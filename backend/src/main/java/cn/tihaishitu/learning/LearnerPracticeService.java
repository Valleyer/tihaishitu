package cn.tihaishitu.learning;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.game.QuestionGradingPolicy;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.learner.LearnerStore;
import cn.tihaishitu.learner.StudyProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LearnerPracticeService {
    public record StartRequest(String intent, String targetKnowledgePointId, String sourceQuestionId,
                               String targetBookId,String targetChapterId) {}
    public record SessionView(String id, String intent, String targetKnowledgePointId, String sourceQuestionId,
                              String targetBookId,String targetChapterId,String currentKnowledgePointId,
                              String status, long revision, AttemptView currentAttempt,
                              boolean flowComplete, boolean canRepeat) {}

    /**
     * 正式做题页顶部信息：来源 + 真题标签 + 全部知识点标签。
     * examLabel 由 exam_year + subject_name 动态生成，displayQuestionNumber 由
     * QuestionNumberFormatter 统一格式化（例如 2014-1 → 1）；原始 questionNumber 仍保留。
     */
    public record AttemptView(String id, String status, String targetKnowledgePointId, String targetKnowledgePointName,
                              String evidenceMode, String diagnosisRole, JsonNode question, JsonNode standard,
                              String explanation, String assessment, String gradingSource, boolean answerRevealed,
                              String sourceName, Integer examYear, String questionNumber, String displayQuestionNumber,
                              String examLabel, List<KnowledgePointTag> knowledgePoints) {
        public AttemptView {
            knowledgePoints = knowledgePoints == null ? List.of() : List.copyOf(knowledgePoints);
        }
    }

    /**
     * Study 页顶部“最近章节”快捷入口的只读视图。
     * 有 active session 时给出 activeSessionId 供“继续章节练习”；只有历史时给出 ended sessionId 供“再次练习”。
     */
    public record RecentChapterView(String status, String activeSessionId, String lastSessionId,
                                    String bookId, String bookName, String chapterId, String chapterName,
                                    String currentKnowledgePointId, Integer currentKnowledgePointIndex,
                                    Integer knowledgePointCount, java.time.Instant updatedAt) {}

    /** 有 active session，可恢复同一 Session。 */
    public static final String RECENT_CHAPTER_ACTIVE = "active";
    /** 没有 active session，但存在历史，需要以相同 Book + Chapter 新建 Session。 */
    public static final String RECENT_CHAPTER_LAST = "last";
    /** 从未进行过章节练习。 */
    public static final String RECENT_CHAPTER_NONE = "none";

    private final LearnerPracticeStore store;
    private final QuestionAttemptStore attempts;
    private final StudyProfileService profiles;
    private final KnowledgeQuestionPoolService pool;
    private final AdaptiveStudyPlanner planner;
    private final LearnerKnowledgeStateService knowledgeStates;
    private final DiagnosticLearningService diagnostics;
    private final DiagnosticLearningStore diagnosisStore;
    private final LearnerStore learners;
    private final ObjectMapper mapper;
    private final QuestionAttemptVariantService variants;
    private final LearnerQuestionProgressStore questionProgress;
    private final RemedialQuestionStore remedial;
    private final QuestionExamMetadataBuilder examMetadataBuilder;

    public LearnerPracticeService(LearnerPracticeStore store, QuestionAttemptStore attempts,
                                  StudyProfileService profiles, KnowledgeQuestionPoolService pool,
                                  AdaptiveStudyPlanner planner, LearnerKnowledgeStateService knowledgeStates,
                                  DiagnosticLearningService diagnostics, DiagnosticLearningStore diagnosisStore,
                                  LearnerStore learners, ObjectMapper mapper,
                                  QuestionAttemptVariantService variants,
                                  LearnerQuestionProgressStore questionProgress,
                                  RemedialQuestionStore remedial,
                                  QuestionExamMetadataBuilder examMetadataBuilder) {
        this.store = store;
        this.attempts = attempts;
        this.profiles = profiles;
        this.pool = pool;
        this.planner = planner;
        this.knowledgeStates = knowledgeStates;
        this.diagnostics = diagnostics;
        this.diagnosisStore = diagnosisStore;
        this.learners = learners;
        this.mapper = mapper;
        this.variants = variants;
        this.questionProgress = questionProgress;
        this.remedial = remedial;
        this.examMetadataBuilder = examMetadataBuilder;
    }

    public List<LearnerPracticeStore.WrongQuestion> wrongQuestions() {
        return store.wrongQuestions(LearnerContext.learnerId());
    }

    /**
     * Chapter 内“当前可练”的知识点数量（Study 章节练习入口的可用量）。
     *
     * <p>长期规则：Playability 只取决于章节内是否存在正式题，不再随每日奖励资格、
     * Review 到期或依赖 readiness 变化，因此不会再出现“题还在但可练数变成 0”。</p>
     */
    public int availableChapterKnowledgePointCount(String learnerId, String bookId, String chapterId) {
        if (learnerId == null || bookId == null || chapterId == null) return 0;
        return availableChapterKnowledgePointCounts(learnerId, bookId, List.of(chapterId))
                .getOrDefault(chapterId, 0);
    }

    /**
     * 整本文集一次批量计算每个 Chapter 的可练知识点数。
     * profile 与 allowed scope 只读取一次，Chapter → KnowledgePoint 归属与候选题判定
     * 各只查一次，随后在 Java 内按 chapterId 分组，消除 /learning/books/{id} 的 N+1。
     */
    public Map<String, Integer> availableChapterKnowledgePointCounts(String learnerId, String bookId,
                                                                    Collection<String> chapterIds) {
        if (learnerId == null || bookId == null || chapterIds == null || chapterIds.isEmpty()) return Map.of();
        Set<String> requested = new LinkedHashSet<>(chapterIds);
        Map<String, Integer> counts = new LinkedHashMap<>();
        requested.forEach(id -> counts.put(id, 0));
        var profile = profiles.rawCurrent(learnerId);
        Set<String> allowed = pool.allowedKnowledgePointIds(new LinkedHashSet<>(profile.selectedBookIds()));
        if (allowed.isEmpty()) return counts;
        Set<String> memberships = new LinkedHashSet<>();
        for (LearnerPracticeStore.ChapterMembership membership : store.bookChapterMemberships(learnerId, bookId)) {
            if (requested.contains(membership.chapterId()) && allowed.contains(membership.knowledgePointId())) {
                memberships.add(membership.chapterId() + "\u0000" + membership.knowledgePointId());
            }
        }
        Set<String> available = new LinkedHashSet<>();
        for (LearnerPracticeStore.ChapterCandidate candidate : store.bookChapterCandidates(learnerId, requested)) {
            if (!memberships.contains(candidate.chapterId() + "\u0000" + candidate.knowledgePointId())) continue;
            if (available.add(candidate.chapterId() + "\u0000" + candidate.knowledgePointId())) {
                counts.merge(candidate.chapterId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    @Transactional
    public SessionView start(StartRequest request) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        String intent = request.intent();
        if (!Set.of("knowledge_drill", "wrong_review", "wrong_drill", "chapter_drill").contains(intent))
            throw bad("练习类型不合法。");
        var profile = profiles.rawCurrent();
        Set<String> allowed = pool.allowedKnowledgePointIds(new LinkedHashSet<>(profile.selectedBookIds()));
        if ("chapter_drill".equals(intent)) return startChapter(request,learnerId,allowed,profile.difficulty());
        if ("wrong_drill".equals(intent)) return startWrongDrill(request, learnerId, allowed, profile.difficulty());
        QuestionDto wrongQuestion = null;
        String targetId = request.targetKnowledgePointId();
        if ("wrong_review".equals(intent)) {
            if (request.sourceQuestionId() == null) throw bad("请选择要重做的错题。");
            var wrong = store.activeWrongQuestion(learnerId, request.sourceQuestionId())
                    .orElseThrow(() -> bad("这道题已不在错题本中。"));
            if (!wrong.available()) throw bad(wrongQuestionUnavailableMessage(wrong.unavailableReason()));
            targetId = wrong.targetKnowledgePointId();
            wrongQuestion = pool.questionForLearner(targetId, allowed, request.sourceQuestionId())
                    .orElseThrow(() -> bad("该题当前不可练习。你仍可将它移出错题本。"));
        } else {
            requirePlayable(targetId, allowed);
        }
        return startWithQuestion(learnerId, intent, targetId,
                "wrong_review".equals(intent) ? request.sourceQuestionId() : null,
                allowed, profile.difficulty(), wrongQuestion);
    }

    /**
     * wrong_drill：从 active 错题中随机连续练习。
     * 只排除下架 / 非 Formal 题，并限制在当前 selected Books 覆盖范围；Session 内不重复。
     */
    private SessionView startWrongDrill(StartRequest request, String learnerId, Set<String> allowed,
                                        String difficulty) {
        List<String> candidates = store.wrongDrillQuestionIds(learnerId, Set.of());
        if (candidates.isEmpty()) {
            throw bad(store.activeWrongQuestionCount(learnerId) == 0
                    ? "错题本还是空的，先去做几道正式题吧。"
                    : "当前学习范围内没有可以练习的错题。可以在错题本中切换学习范围，或稍后再试。");
        }
        String questionId = randomOf(candidates);
        var wrong = store.activeWrongQuestion(learnerId, questionId)
                .orElseThrow(() -> bad("这道题已不在错题本中。"));
        QuestionDto question = pool.questionForLearner(wrong.targetKnowledgePointId(), allowed, questionId)
                .orElseThrow(() -> bad("当前学习范围内没有可以练习的错题。"));
        return startWithQuestion(learnerId, "wrong_drill", wrong.targetKnowledgePointId(), null,
                allowed, difficulty, question);
    }

    private SessionView startWithQuestion(String learnerId, String intent, String targetId, String sourceQuestionId,
                                          Set<String> allowed, String difficulty, QuestionDto question) {
        String id = UUID.randomUUID().toString();
        store.create(id, learnerId, intent, targetId, sourceQuestionId, allowed);
        QuestionDto frozen = question;
        String frozenTargetId = targetId;
        PracticeActionContext.within(learnerId, id, () -> {
            String attemptId = frozen == null
                    ? draw(id, learnerId, frozenTargetId, allowed, difficulty, null)
                    : createAttempt(learnerId, frozenTargetId, frozen, "normal", null);
            store.setCurrentAttempt(id, learnerId, attemptId);
            return null;
        });
        return get(id);
    }

    private static String randomOf(List<String> values) {
        return values.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(values.size()));
    }

    public SessionView get(String id) {
        String learnerId = LearnerContext.learnerId();
        LearnerPracticeStore.Session session = store.find(id, learnerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "专项练习不存在。"));
        return view(session);
    }

    @Transactional
    public void removeWrongQuestion(String questionId) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        if (!store.removeWrongQuestion(learnerId, questionId, Instant.now()))
            throw new ApiException(HttpStatus.NOT_FOUND, "错题记录不存在或已经移出。");
    }

    @Transactional
    public SessionView answer(String id, String attemptId, String questionId, JsonNode answer) {
        return mutate(id, attemptId, snapshot -> {
            if (!snapshot.questionId().equals(questionId)) throw conflict("题目已经变化，请重新载入。");
            boolean correct = QuestionGradingPolicy.matches(snapshot.standard(), answer);
            Instant occurredAt = Instant.now();
            if (!attempts.recordAnswer(snapshot, answer, correct, occurredAt))
                throw conflict("这道题已经完成评分。");
            grade(snapshot,correct ? "correct" : "wrong","automatic",occurredAt,id);
        });
    }

    @Transactional
    public SessionView reveal(String id, String attemptId, String questionId) {
        return mutate(id, attemptId, snapshot -> {
            if (!snapshot.questionId().equals(questionId)) throw conflict("题目已经变化，请重新载入。");
            attempts.reveal(snapshot);
        });
    }

    @Transactional
    public SessionView selfAssess(String id, String attemptId, String questionId, String assessment) {
        return mutate(id, attemptId, snapshot -> {
            if (!snapshot.questionId().equals(questionId)) throw conflict("题目已经变化，请重新载入。");
            Instant occurredAt = Instant.now();
            if (!attempts.recordSelfAssessment(snapshot, assessment, occurredAt))
                throw conflict("请先查看参考解答，或此题已经完成自评。");
            grade(snapshot,assessment,"self",occurredAt,id);
        });
    }

    @Transactional
    public SessionView next(String id) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        LearnerPracticeStore.Session session = requireActive(store.lock(id, learnerId));
        QuestionAttemptStore.Snapshot current = attempts.findForPractice(
                session.currentAttemptId(), learnerId, id);
        if (!"graded".equals(current.status())) throw conflict("请先完成当前题目。");
        Set<String> allowed = store.scope(id);
        String difficulty = profiles.rawCurrent().difficulty();
        PracticeActionContext.within(learnerId, id, () -> {
            DiagnosticLearningStore.Session diagnosis = diagnosisForCurrent(id, current);
            String nextId;
            RemedialQuestionStore.ParentInfo parent = remedial.parentInfo(current.questionId());
            if(parent!=null){
                List<RemedialQuestionStore.Step> steps=remedial.steps(parent.parentQuestionId());
                RemedialQuestionStore.Step nextStep=steps.stream().filter(step->step.order()>parent.order()).findFirst().orElse(null);
                nextId=nextStep==null?createStoredAttempt(learnerId,session.currentKnowledgePointId()!=null?session.currentKnowledgePointId():session.targetKnowledgePointId(),remedial.parent(parent.parentQuestionId()),"normal",null):createStoredAttempt(learnerId,current.targetKnowledgePointId(),nextStep,"remedial",null);
            } else if(isFirstFailedParent(id,current)){
                RemedialQuestionStore.Step first=remedial.steps(current.questionId()).get(0);
                nextId=createStoredAttempt(learnerId,current.targetKnowledgePointId(),first,"remedial",null);
            // 错题快练是连续刷错题，不参与综合题诊断状态机；即使历史上留下了诊断会话，
            // 也继续按“下一道未见错题”推进，而不是卡在诊断里。
            } else if (diagnosis != null && !Set.of("resolved", "abandoned").contains(diagnosis.status())
                    && !"wrong_drill".equals(session.intent())) {
                DiagnosticLearningService.Directive directive = diagnostics.nextDirective(diagnosis.id());
                nextId = draw(id, learnerId, directive.targetKnowledgePointId(), allowed, difficulty, directive);
            } else {
                if ("wrong_review".equals(session.intent()))
                    throw conflict("本轮错题流程已经完成，可以结束练习。");
                if ("wrong_drill".equals(session.intent())) {
                    nextId = drawWrongDrill(session, learnerId, allowed, difficulty);
                } else if("chapter_drill".equals(session.intent())){
                    String point=nextChapterPoint(session,allowed,difficulty);
                    if(point==null)throw conflict("本轮章节可练题目已完成，可以结束练习。");
                    store.setCurrentKnowledgePoint(id,learnerId,point);
                    nextId=draw(id,learnerId,point,allowed,difficulty,null);
                }else nextId = draw(id, learnerId, session.targetKnowledgePointId(), allowed, difficulty, null);
            }
            store.setCurrentAttempt(id, learnerId, nextId);
            return null;
        });
        return get(id);
    }

    @Transactional
    public SessionView end(String id) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        LearnerPracticeStore.Session session = requireActive(store.lock(id, learnerId));
        diagnosisStore.latestForPractice(id).ifPresent(diagnosis -> {
            if (!Set.of("resolved", "abandoned").contains(diagnosis.status())) {
                PracticeActionContext.within(learnerId, id, () -> {
                    diagnostics.abandon(diagnosis.id());
                    return null;
                });
            }
        });
        store.end(id, learnerId, Instant.now());
        return get(id);
    }

    private SessionView mutate(String id, String attemptId,
                               java.util.function.Consumer<QuestionAttemptStore.Snapshot> action) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        LearnerPracticeStore.Session session = requireActive(store.lock(id, learnerId));
        if (!attemptId.equals(session.currentAttemptId())) throw conflict("题目已经变化，请重新载入。");
        PracticeActionContext.within(learnerId, id, () -> {
            action.accept(attempts.findForPractice(attemptId, learnerId, id));
            return null;
        });
        return get(id);
    }

    private String draw(String sessionId, String learnerId, String targetId, Set<String> allowed,
                        String profileDifficulty, DiagnosticLearningService.Directive directive) {
        AdaptiveStudyPlanner.QuestionContext context = planner.questionContext(
                learnerId, allowed, targetId, profileDifficulty);
        boolean training = directive != null && "training".equals(directive.evidenceMode());
        int preferred = directive != null && DiagnosticLearningService.DEPENDENCY_PROBE.equals(directive.role())
                ? Math.min(3, context.preferredDifficulty()) : context.preferredDifficulty();
        var request = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(targetId, allowed,
                store.seenQuestions(sessionId), preferred,
                training ? KnowledgeQuestionPoolService.Mode.TRAINING : KnowledgeQuestionPoolService.Mode.NORMAL);
        if (directive != null && DiagnosticLearningService.DEPENDENCY_PROBE.equals(directive.role())
                && pool.eligibleQuestionsForLearner(request).isEmpty()) {
            diagnostics.markProbeUnavailable(directive.diagnosisSessionId(), targetId);
            DiagnosticLearningService.Directive next = diagnostics.nextDirective(directive.diagnosisSessionId());
            return draw(sessionId, learnerId, next.targetKnowledgePointId(), allowed, profileDifficulty, next);
        }
        QuestionDto question = directive == null
                ? pool.selectKnowledgeDrillQuestion(learnerId, request)
                : pool.selectQuestionForLearner(learnerId, request);
        return createAttempt(learnerId, targetId, question, training ? "training" : "normal", directive);
    }

    /**
     * wrong_drill 的下一题：从本 Session 尚未见过的 active 错题中随机。
     * 全部做完时给出“本轮完成”的明确冲突响应，而不是 500。
     */
    private String drawWrongDrill(LearnerPracticeStore.Session session, String learnerId,
                                  Set<String> allowed, String difficulty) {
        List<String> candidates = store.wrongDrillQuestionIds(learnerId, store.seenQuestions(session.id()));
        while (!candidates.isEmpty()) {
            String questionId = randomOf(candidates);
            var wrong = store.activeWrongQuestion(learnerId, questionId).orElse(null);
            if (wrong == null || !wrong.available()) {
                candidates.remove(questionId);
                continue;
            }
            QuestionDto question = pool.questionForLearner(wrong.targetKnowledgePointId(), allowed, questionId)
                    .orElse(null);
            if (question == null) {
                candidates.remove(questionId);
                continue;
            }
            store.setCurrentKnowledgePoint(session.id(), learnerId, wrong.targetKnowledgePointId());
            // 错题快练是“连续刷错题”，证据模式仍是 normal（Mastery 规则不变），
            // 但 grade() 会跳过综合题诊断，避免快练被诊断状态机打断。
            return createAttempt(learnerId, wrong.targetKnowledgePointId(), question, "normal", null);
        }
        throw conflict("本轮错题快练已经完成，可以结束练习。");
    }

    private String createAttempt(String learnerId, String targetId, QuestionDto question, String evidenceMode,
                                  DiagnosticLearningService.Directive directive) {
        String id = UUID.randomUUID().toString();
        ObjectNode full = mapper.valueToTree(question);
        // 题面 metadata 由共享 builder 统一生成（Hub 与 World 共用同一套规则），
        // 发题时冻结进快照：刷新同一 attempt 结果稳定，前端不需要猜来源。
        full.set("examMetadata", examMetadataBuilder.build(question.id(), question.chapter()));
        var previous = questionProgress.latestAttemptForQuestion(learnerId, question.id()).orElse(null);
        QuestionAttemptVariantService.AttemptVariant variant = variants.create(full, question.answer(),
                previous == null ? full : previous.questionSnapshot(),
                previous == null ? question.answer() : previous.standardAnswer());
        attempts.create(id, null, question.id(), variant.question(), variant.standard(),
                full.path("gradingMode").asText("auto"), targetId, evidenceMode, question.difficulty(),
                directive == null ? null : directive.diagnosisSessionId(),
                directive == null ? null : directive.role());
        return id;
    }

    private SessionView view(LearnerPracticeStore.Session session) {
        QuestionAttemptStore.Snapshot snapshot = session.currentAttemptId() == null ? null
                : attempts.findForPractice(session.currentAttemptId(), session.learnerId(), session.id());
        AttemptView attempt = snapshot == null ? null : attemptView(snapshot);
        boolean complete = snapshot != null && flowComplete(session, snapshot);
        boolean canRepeat = complete && Set.of("knowledge_drill","chapter_drill","wrong_drill").contains(session.intent())
                && hasNext(session);
        return new SessionView(session.id(), session.intent(), session.targetKnowledgePointId(),
                session.sourceQuestionId(),session.targetBookId(),session.targetChapterId(),session.currentKnowledgePointId(),
                session.status(), session.revision(), attempt, complete,
                canRepeat);
    }

    /**
     * 题面元数据：来源 + 真题标签 + 全部知识点标签（core / auxiliary 都返回）。
     * 元数据在 attempt 创建时冻结进 question_snapshot_json，刷新同一 attempt 结果稳定。
     */
    private AttemptView attemptView(QuestionAttemptStore.Snapshot snapshot) {
        ObjectNode visible = snapshot.question().deepCopy();
        visible.remove(List.of("answer", "aliases", "keywords", "explanation"));
        String targetName = pool.knowledgeDetails(List.of(snapshot.targetKnowledgePointId())).stream()
                .findFirst().map(KnowledgePointDto::name).orElse("当前知识点");
        boolean revealed = Set.of("revealed", "graded").contains(snapshot.status());
        JsonNode metadata = visible.path("examMetadata");
        String sourceName = metadata.path("sourceName").asText(null);
        Integer examYear = metadata.path("examYear").isNumber() ? metadata.path("examYear").asInt() : null;
        String questionNumber = metadata.path("questionNumber").asText(null);
        String displayQuestionNumber = metadata.path("displayQuestionNumber").asText(null);
        String examLabel = metadata.path("examLabel").asText(null);
        List<KnowledgePointTag> tags = new java.util.ArrayList<>();
        metadata.path("knowledgePoints").forEach(tag -> tags.add(new KnowledgePointTag(
                tag.path("id").asText(null), tag.path("name").asText(null), tag.path("role").asText(null))));
        return new AttemptView(snapshot.id(), snapshot.status(), snapshot.targetKnowledgePointId(), targetName,
                snapshot.evidenceMode(), snapshot.diagnosisRole(), visible,
                revealed ? snapshot.standard() : null,
                revealed ? snapshot.question().path("explanation").asText() : null,
                snapshot.assessment(), snapshot.gradingSource(), revealed,
                sourceName, examYear, questionNumber, displayQuestionNumber, examLabel, tags);
    }

    private boolean flowComplete(LearnerPracticeStore.Session session, QuestionAttemptStore.Snapshot snapshot) {
        if (!"graded".equals(snapshot.status())) return false;
        RemedialQuestionStore.ParentInfo parent=remedial.parentInfo(snapshot.questionId());
        if(parent!=null)return false;
        if(isFirstFailedParent(session.id(),snapshot))return false;
        DiagnosticLearningStore.Session diagnosis = diagnosisForCurrent(session.id(), snapshot);
        if (diagnosis != null) return "resolved".equals(diagnosis.status());
        if ("training".equals(snapshot.evidenceMode())) return "correct".equals(snapshot.assessment());
        return true;
    }

    private DiagnosticLearningStore.Session diagnosisForCurrent(
            String practiceSessionId, QuestionAttemptStore.Snapshot snapshot) {
        DiagnosticLearningStore.Session diagnosis = diagnosisStore.latestForPractice(practiceSessionId).orElse(null);
        if (diagnosis == null) return null;
        boolean rootAttempt = snapshot.id().equals(diagnosis.rootAttemptId());
        boolean diagnosisAttempt = diagnosis.id().equals(snapshot.diagnosisSessionId());
        return rootAttempt || diagnosisAttempt ? diagnosis : null;
    }

    /**
     * 知识点专项的发题条件：知识点在当前所选文集范围内，且存在至少一道正式题。
     * 不再校验依赖 readiness，也不再因“今天已经答对 / Review 未到期”拒绝发题。
     */
    private void requirePlayable(String targetId, Set<String> allowed) {
        if (targetId == null || !allowed.contains(targetId)) throw bad("知识点不在当前所选文集范围内。");
        var request = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(
                targetId, allowed, Set.of(), 3, KnowledgeQuestionPoolService.Mode.NORMAL);
        if (pool.eligibleQuestionsForLearner(request).isEmpty())
            throw bad("当前知识点暂无可用于专项练习的正式题。");
    }

    private boolean hasNext(LearnerPracticeStore.Session session) {
        if (!"active".equals(session.status())) return false;
        Set<String> allowed = store.scope(session.id());
        var profile = profiles.rawCurrent();
        if("chapter_drill".equals(session.intent()))return nextChapterPoint(session,allowed,profile.difficulty())!=null;
        if("wrong_drill".equals(session.intent()))
            return !store.wrongDrillQuestionIds(session.learnerId(), store.seenQuestions(session.id())).isEmpty();
        AdaptiveStudyPlanner.QuestionContext context = planner.questionContext(
                session.learnerId(), allowed, session.targetKnowledgePointId(), profile.difficulty());
        var request = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(
                session.targetKnowledgePointId(), allowed,
                store.seenQuestions(session.id()), context.preferredDifficulty(),
                KnowledgeQuestionPoolService.Mode.NORMAL);
        return !pool.eligibleKnowledgeDrillQuestions(session.learnerId(), request).isEmpty();
    }

    public SessionView latestChapter(){
        return store.latestActiveChapter(LearnerContext.learnerId()).map(this::view).orElse(null);
    }

    /**
     * Study 页顶部“最近章节”快捷入口。
     *
     * <ul>
     *   <li>有 active chapter session：返回 activeSessionId，前端“继续章节练习”恢复同一 Session；</li>
     *   <li>无 active 但有历史：返回 lastSessionId 与 Book + Chapter，前端以相同范围新建 Session；</li>
     *   <li>从未练过：返回 status=none。</li>
     * </ul>
     *
     * 不让前端从大量 attempt 自己推导。
     */
    public RecentChapterView recentChapter() {
        String learnerId = LearnerContext.learnerId();
        LearnerPracticeStore.Session active = store.latestActiveChapter(learnerId).orElse(null);
        LearnerPracticeStore.Session latest = active != null ? active
                : store.latestChapterSession(learnerId).orElse(null);
        if (latest == null) {
            return new RecentChapterView(RECENT_CHAPTER_NONE, null, null, null, null, null, null,
                    null, null, null, null);
        }
        var names = store.chapterNames(latest.targetBookId(), latest.targetChapterId()).orElse(null);
        String currentPointId = latest.currentKnowledgePointId() != null
                ? latest.currentKnowledgePointId() : latest.targetKnowledgePointId();
        Integer index = null;
        Integer count = null;
        if (latest.targetBookId() != null && latest.targetChapterId() != null) {
            List<String> points = store.chapterKnowledgePoints(learnerId, latest.targetBookId(),
                    latest.targetChapterId());
            count = points.size();
            int position = points.indexOf(currentPointId);
            index = position < 0 ? null : position + 1;
        }
        return new RecentChapterView(active != null ? RECENT_CHAPTER_ACTIVE : RECENT_CHAPTER_LAST,
                active == null ? null : active.id(), latest.id(),
                latest.targetBookId(), names == null ? null : names.bookName(),
                latest.targetChapterId(), names == null ? null : names.chapterName(),
                currentPointId, index, count, latest.updatedAt());
    }

    private SessionView startChapter(StartRequest request,String learnerId,Set<String> allowed,String difficulty){
        if(request.targetBookId()==null||request.targetChapterId()==null)throw bad("请选择文集和章节。");
        List<String> points=store.chapterKnowledgePoints(learnerId,request.targetBookId(),request.targetChapterId());
        String point=points.stream().filter(id->available(learnerId,id,allowed,difficulty,Set.of())).findFirst().orElseThrow(()->bad("这个章节当前没有待练的新题。"));
        String id=UUID.randomUUID().toString();store.createChapter(id,learnerId,request.targetBookId(),request.targetChapterId(),point,allowed);
        PracticeActionContext.within(learnerId,id,()->{String attempt=draw(id,learnerId,point,allowed,difficulty,null);store.setCurrentAttempt(id,learnerId,attempt);return null;});
        return get(id);
    }

    private void grade(QuestionAttemptStore.Snapshot snapshot,String assessment,String source,Instant at,String practiceId){
        if (remedial.parentInfo(snapshot.questionId()) != null) return;
        boolean parentFailure=Set.of("wrong","partial").contains(assessment)&&!remedial.steps(snapshot.questionId()).isEmpty();
        // 错题快练是连续刷错题，不进入综合题诊断状态机：直接按目标知识点记账。
        if(parentFailure||isQuickDrill(practiceId))knowledgeStates.apply(snapshot,assessment,source,at);
        else diagnostics.handleGradedAttempt(snapshot,assessment,source,at,store.scope(practiceId));
    }

    /** 当前是否处于“快速练习错题”Session。 */
    private boolean isQuickDrill(String practiceId){
        if(practiceId==null)return false;
        return store.find(practiceId,LearnerContext.learnerId())
                .map(session->"wrong_drill".equals(session.intent())).orElse(false);
    }

    private boolean isFirstFailedParent(String sessionId,QuestionAttemptStore.Snapshot snapshot){
        return Set.of("wrong","partial").contains(snapshot.assessment())
                && !remedial.steps(snapshot.questionId()).isEmpty()&&store.attemptCount(sessionId,snapshot.questionId())==1;
    }

    private String createStoredAttempt(String learnerId,String targetId,RemedialQuestionStore.Step step,String evidenceMode,String role){
        String id=UUID.randomUUID().toString();var previous=questionProgress.latestAttemptForQuestion(learnerId,step.id()).orElse(null);
        var variant=variants.create(step.question(),step.standard(),previous==null?step.question():previous.questionSnapshot(),previous==null?step.standard():previous.standardAnswer());
        attempts.create(id,null,step.id(),variant.question(),variant.standard(),step.gradingMode(),targetId,evidenceMode,step.difficulty(),null,role);return id;
    }

    private String nextChapterPoint(LearnerPracticeStore.Session session,Set<String> allowed,String difficulty){
        List<String> points=store.chapterKnowledgePoints(session.learnerId(),session.targetBookId(),session.targetChapterId());
        if(points.isEmpty())return null;int current=Math.max(0,points.indexOf(session.currentKnowledgePointId()));
        Set<String> seen=store.seenQuestions(session.id());
        for(int offset=1;offset<=points.size();offset++){String point=points.get((current+offset)%points.size());if(available(session.learnerId(),point,allowed,difficulty,seen))return point;}return null;
    }

    private boolean available(String learnerId,String point,Set<String> allowed,String difficulty,Set<String> seen){
        var request=new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(point,allowed,seen,
                preferredDifficulty(learnerId,allowed,point,difficulty),KnowledgeQuestionPoolService.Mode.NORMAL);
        return !pool.eligibleKnowledgeDrillQuestions(learnerId,request).isEmpty();
    }

    /** 难度只作为出题软提示，不阻止任何正式题被抽中。 */
    private int preferredDifficulty(String learnerId,Set<String> allowed,String point,String difficulty){
        return planner.questionContext(learnerId,allowed,point,difficulty).preferredDifficulty();
    }

    private static boolean needsTraining(QuestionAttemptStore.Snapshot snapshot) {
        return Set.of("wrong", "partial").contains(snapshot.assessment())
                && (snapshot.diagnosisRole() == null || "training".equals(snapshot.evidenceMode()));
    }
    private static LearnerPracticeStore.Session requireActive(LearnerPracticeStore.Session session) {
        if (!"active".equals(session.status())) throw conflict("专项练习已经结束。");
        return session;
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }

    /** 错题卡不可练习时的用户文案；原因由 LearnerPracticeStore.wrongQuestions 的 SQL 判定。 */
    private static String wrongQuestionUnavailableMessage(String unavailableReason) {
        if ("out_of_scope".equals(unavailableReason)) {
            return "该题当前不在所选文集范围内。你仍可将它移出错题本。";
        }
        if ("knowledge_unavailable".equals(unavailableReason)) {
            return "该题所属知识点当前不可练习。你仍可将它移出错题本。";
        }
        return "该题当前不可练习。你仍可将它移出错题本。";
    }
}

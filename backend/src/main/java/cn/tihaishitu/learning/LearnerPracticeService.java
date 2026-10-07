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
import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Hub Practice：KNOWLEDGE / CHAPTER / WRONG 三套正式选题策略的入口。
 *
 * <p>PR3 之后普通正式训练彻底单层化：一道 Formal Parent Question →
 * 一次作答 / 自评 → 一次 grading → 写 Wrong Book / Mastery / Evidence →
 * 直接进入下一道普通 Formal Question 或结束当前流程。
 * 不再自动进入 Diagnosis / Remedial 嵌套，也不再在答错后 retry 同一道父题。</p>
 *
 * <p>RANDOM 策略只属于 Learner World（{@code GameActionService} + {@link RandomPracticeSelector}），
 * 本类不提供 RANDOM intent。</p>
 */
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
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
     *
     * <p>进度字段沿用既有 JSON 名称，但 PR3 后含义是“当前题在章节确定性题序中的位置 / 题序长度”，
     * 因为 Chapter 的推进粒度已经是 Question 而不是 KnowledgePoint。</p>
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
    private final LearnerKnowledgeStateService knowledgeStates;
    private final DiagnosticLearningService diagnostics;
    private final DiagnosticLearningStore diagnosisStore;
    private final LearnerStore learners;
    private final ObjectMapper mapper;
    private final QuestionAttemptVariantService variants;
    private final LearnerQuestionProgressStore questionProgress;
    private final QuestionExamMetadataBuilder examMetadataBuilder;
    private final KnowledgePracticeSelector knowledge;
    private final ChapterPracticeSelector chapters;
    private final WrongPracticeSelector wrongs;

    public LearnerPracticeService(LearnerPracticeStore store, QuestionAttemptStore attempts,
                                  StudyProfileService profiles, KnowledgeQuestionPoolService pool,
                                  LearnerKnowledgeStateService knowledgeStates,
                                  DiagnosticLearningService diagnostics, DiagnosticLearningStore diagnosisStore,
                                  LearnerStore learners, ObjectMapper mapper,
                                  QuestionAttemptVariantService variants,
                                  LearnerQuestionProgressStore questionProgress,
                                  QuestionExamMetadataBuilder examMetadataBuilder,
                                  KnowledgePracticeSelector knowledge, ChapterPracticeSelector chapters,
                                  WrongPracticeSelector wrongs) {
        this.store = store;
        this.attempts = attempts;
        this.profiles = profiles;
        this.pool = pool;
        this.knowledgeStates = knowledgeStates;
        this.diagnostics = diagnostics;
        this.diagnosisStore = diagnosisStore;
        this.learners = learners;
        this.mapper = mapper;
        this.variants = variants;
        this.questionProgress = questionProgress;
        this.examMetadataBuilder = examMetadataBuilder;
        this.knowledge = knowledge;
        this.chapters = chapters;
        this.wrongs = wrongs;
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
        Set<String> allowed = pool.allowedKnowledgePointIds(
                new LinkedHashSet<>(profiles.rawCurrent().selectedBookIds()));
        return switch (intent) {
            case "chapter_drill" -> startChapter(request, learnerId, allowed);
            case "wrong_drill" -> startWrongDrill(learnerId, allowed);
            case "wrong_review" -> startWrongReview(request, learnerId, allowed);
            default -> startKnowledge(learnerId, request.targetKnowledgePointId(), allowed);
        };
    }

    /**
     * 知识点专项：Session 内随机不重复；候选耗尽后本轮完成，新 Session 重新对完整池洗牌。
     * 不再校验依赖 readiness，也不再因“今天已经答对 / Review 未到期”拒绝发题。
     */
    private SessionView startKnowledge(String learnerId, String targetId, Set<String> allowed) {
        if (targetId == null || !allowed.contains(targetId)) throw bad("知识点不在当前所选文集范围内。");
        if (knowledge.candidateQuestionIds(targetId, allowed, Set.of()).isEmpty())
            throw bad("当前知识点暂无可用于专项练习的正式题。");
        String id = UUID.randomUUID().toString();
        store.create(id, learnerId, "knowledge_drill", targetId, null, allowed);
        PracticeActionContext.within(learnerId, id, () -> {
            String questionId = knowledge.select(targetId, allowed, Set.of())
                    .orElseThrow(() -> bad("当前知识点暂无可用于专项练习的正式题。"));
            store.setCurrentAttempt(id, learnerId, createAttempt(learnerId, targetId, questionId,
                    PracticeDrawMode.KNOWLEDGE));
            return null;
        });
        return get(id);
    }

    /**
     * 章节练习：固定确定性题序 + 跨 Session 持久 cursor + 末尾 wrap。
     * 不再“KP 顺序 + KP 内随机”，也不再因为当前 KP 无题而返回“没有待练的新题”。
     */
    private SessionView startChapter(StartRequest request, String learnerId, Set<String> allowed) {
        if (request.targetBookId() == null || request.targetChapterId() == null) throw bad("请选择文集和章节。");
        ChapterPracticeSelector.Step step = chapters
                .next(learnerId, request.targetBookId(), request.targetChapterId(), allowed)
                .orElseThrow(() -> bad("这个章节当前没有可练的正式题。"));
        String id = UUID.randomUUID().toString();
        store.createChapter(id, learnerId, request.targetBookId(), request.targetChapterId(),
                step.targetKnowledgePointId(), allowed);
        PracticeActionContext.within(learnerId, id, () -> {
            store.setCurrentAttempt(id, learnerId, createAttempt(learnerId, step.targetKnowledgePointId(),
                    step.questionId(), PracticeDrawMode.CHAPTER));
            return null;
        });
        return get(id);
    }

    /**
     * wrong_review：用户从错题本点选某一题，只重做这一题；graded 后流程完成。
     * 不插诊断、不插补救子题。
     */
    private SessionView startWrongReview(StartRequest request, String learnerId, Set<String> allowed) {
        if (request.sourceQuestionId() == null) throw bad("请选择要重做的错题。");
        var wrong = store.activeWrongQuestion(learnerId, request.sourceQuestionId())
                .orElseThrow(() -> bad("这道题已不在错题本中。"));
        if (!wrong.available()) throw bad(wrongQuestionUnavailableMessage(wrong.unavailableReason()));
        QuestionDto question = pool.questionForLearner(wrong.targetKnowledgePointId(), allowed,
                        request.sourceQuestionId())
                .orElseThrow(() -> bad("该题当前不可练习。你仍可将它移出错题本。"));
        String id = UUID.randomUUID().toString();
        store.create(id, learnerId, "wrong_review", wrong.targetKnowledgePointId(), request.sourceQuestionId(), allowed);
        PracticeActionContext.within(learnerId, id, () -> {
            store.setCurrentAttempt(id, learnerId, createAttempt(learnerId, wrong.targetKnowledgePointId(),
                    question.id(), PracticeDrawMode.WRONG));
            return null;
        });
        return get(id);
    }

    /**
     * wrong_drill：从 active 永久错题中 Session 内随机不重复地连续练习。
     * 全部耗尽后本轮完成；新 Session 重新对完整池洗牌。
     */
    private SessionView startWrongDrill(String learnerId, Set<String> allowed) {
        WrongPracticeSelector.Selection selection = wrongs.select(learnerId, allowed, Set.of())
                .orElseThrow(() -> bad(store.activeWrongQuestionCount(learnerId) == 0
                        ? "错题本还是空的，先去做几道正式题吧。"
                        : "当前学习范围内没有可以练习的错题。可以在错题本中切换学习范围，或稍后再试。"));
        String id = UUID.randomUUID().toString();
        store.create(id, learnerId, "wrong_drill", selection.targetKnowledgePointId(), null, allowed);
        PracticeActionContext.within(learnerId, id, () -> {
            store.setCurrentAttempt(id, learnerId, createAttempt(learnerId, selection.targetKnowledgePointId(),
                    selection.questionId(), PracticeDrawMode.WRONG));
            return null;
        });
        return get(id);
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
            grade(snapshot,correct ? "correct" : "wrong","automatic",occurredAt);
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
                throw conflict("请先查看参考解析，或此题已经完成自评。");
            grade(snapshot,assessment,"self",occurredAt);
        });
    }

    /** 完成当前题后进入下一道普通正式题；四模式各自的推进规则完全独立。 */
    @Transactional
    public SessionView next(String id) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        LearnerPracticeStore.Session session = requireActive(store.lock(id, learnerId));
        QuestionAttemptStore.Snapshot current = attempts.findForPractice(
                session.currentAttemptId(), learnerId, id);
        if (!"graded".equals(current.status())) throw conflict("请先完成当前题目。");
        Set<String> allowed = store.scope(id);
        Set<String> seen = store.seenQuestions(id);
        PracticeActionContext.within(learnerId, id, () -> {
            store.setCurrentAttempt(id, learnerId, drawNext(session, learnerId, allowed, seen));
            return null;
        });
        return get(id);
    }

    private String drawNext(LearnerPracticeStore.Session session, String learnerId,
                            Set<String> allowed, Set<String> seen) {
        return switch (session.intent()) {
            case "chapter_drill" -> drawChapterNext(session, learnerId, allowed);
            case "wrong_drill" -> drawWrongDrillNext(session, learnerId, allowed, seen);
            // 单题错题重做：当前指定题 graded 即 complete，不再续题。
            case "wrong_review" -> throw conflict("本轮错题流程已经完成，可以结束练习。");
            case "knowledge_drill" -> drawKnowledgeNext(session, learnerId, allowed, seen);
            default -> throw bad("练习类型不合法。");
        };
    }

    /** Chapter：固定题序 + 持久 cursor 的 successor（末尾 wrap）。 */
    private String drawChapterNext(LearnerPracticeStore.Session session, String learnerId, Set<String> allowed) {
        ChapterPracticeSelector.Step step = chapters
                .next(learnerId, session.targetBookId(), session.targetChapterId(), allowed)
                .orElseThrow(() -> conflict("这个章节当前没有可练的正式题。"));
        store.setCurrentKnowledgePoint(session.id(), learnerId, step.targetKnowledgePointId());
        return createAttempt(learnerId, step.targetKnowledgePointId(), step.questionId(),
                PracticeDrawMode.CHAPTER);
    }

    /** Knowledge：本 Session 随机池耗尽即结束，不再有任何诊断 / 补救续题。 */
    private String drawKnowledgeNext(LearnerPracticeStore.Session session, String learnerId,
                                     Set<String> allowed, Set<String> seen) {
        String questionId = knowledge.select(session.targetKnowledgePointId(), allowed, seen)
                .orElseThrow(() -> conflict("本 Session 的专项题已经全部做过，本轮完成。"));
        return createAttempt(learnerId, session.targetKnowledgePointId(), questionId,
                PracticeDrawMode.KNOWLEDGE);
    }

    /** Wrong drill：本 Session active 错题池耗尽即结束。 */
    private String drawWrongDrillNext(LearnerPracticeStore.Session session, String learnerId,
                                      Set<String> allowed, Set<String> seen) {
        WrongPracticeSelector.Selection selection = wrongs.select(learnerId, allowed, seen)
                .orElseThrow(() -> conflict("本轮错题快练已经完成，可以结束练习。"));
        store.setCurrentKnowledgePoint(session.id(), learnerId, selection.targetKnowledgePointId());
        return createAttempt(learnerId, selection.targetKnowledgePointId(), selection.questionId(),
                PracticeDrawMode.WRONG);
    }

    /**
     * 结束 Session。部署前可能残留未完成 diagnosis，一并标记 abandoned，
     * 保证旧 active session 可继续、不死锁。
     */
    @Transactional
    public SessionView end(String id) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        LearnerPracticeStore.Session session = requireActive(store.lock(id, learnerId));
        diagnosisStore.latestForPractice(id).ifPresent(diagnosis -> {
            if (!Set.of("resolved", "abandoned").contains(diagnosis.status())) {
                PracticeActionContext.within(learnerId, id, () -> {
                    diagnostics.abandonIfPresent(diagnosis.id());
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

    /**
     * 普通正式训练单层化：一次 grading 只做一件事——
     * 对本次 Attempt 冻结的唯一 target KnowledgePoint 记 Mastery / Evidence。
     * 错题 / partial 的 Wrong Book 写入已经由 {@code QuestionAttemptStore} 在判题时完成。
     * 不再调用 DiagnosticLearningService，也不再发 Remedial 子题或 retry 父题。
     */
    private void grade(QuestionAttemptStore.Snapshot snapshot, String assessment, String source, Instant at) {
        knowledgeStates.apply(snapshot, assessment, source, at);
    }

    /**
     * 正式 Attempt 创建的统一入口。
     * 复用 QuestionExamMetadataBuilder + QuestionAttemptVariantService + QuestionAttemptStore，
     * 不复制第二套发题快照逻辑；draw_mode 由本策略在服务端决定，前端不提交。
     */
    private String createAttempt(String learnerId, String targetId, String questionId, PracticeDrawMode drawMode) {
        QuestionDto question = pool.questionForLearner(questionId)
                .orElseThrow(() -> bad("这道题当前不可练习。"));
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
                full.path("gradingMode").asText("auto"), targetId, "normal", question.difficulty(),
                null, null, drawMode.wireValue(), null);
        return id;
    }

    private SessionView view(LearnerPracticeStore.Session session) {
        QuestionAttemptStore.Snapshot snapshot = session.currentAttemptId() == null ? null
                : attempts.findForPractice(session.currentAttemptId(), session.learnerId(), session.id());
        AttemptView attempt = snapshot == null ? null : attemptView(snapshot);
        // 当前题 graded 就代表“这一题流程完成”；canRepeat 表示还有没有下一道普通正式题。
        boolean complete = snapshot != null && "graded".equals(snapshot.status());
        boolean canRepeat = complete && hasNext(session);
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
                revealed && !"self_assessment".equals(snapshot.gradingMode()) ? snapshot.standard() : null,
                revealed ? snapshot.question().path("explanation").asText() : null,
                snapshot.assessment(), snapshot.gradingSource(), revealed,
                sourceName, examYear, questionNumber, displayQuestionNumber, examLabel, tags);
    }

    /**
     * 下一道普通正式题是否存在（决定 canRepeat）：
     * <pre>
     * knowledge   本 Session 随机池还没耗尽
     * chapter     连续 next，末尾 wrap，永远不为永久 complete
     * wrong_drill 本 Session active 错题池还没耗尽
     * wrong_review 当前指定题 graded 即 complete
     * </pre>
     */
    private boolean hasNext(LearnerPracticeStore.Session session) {
        if (!"active".equals(session.status())) return false;
        Set<String> allowed = store.scope(session.id());
        Set<String> seen = store.seenQuestions(session.id());
        return switch (session.intent()) {
            case "chapter_drill" -> !chapters.sequence(session.learnerId(), session.targetBookId(),
                    session.targetChapterId(), allowed).isEmpty();
            case "wrong_drill" -> wrongs.hasRemaining(session.learnerId(), seen);
            case "wrong_review" -> false;
            case "knowledge_drill" -> knowledge.hasRemaining(session.targetKnowledgePointId(), allowed, seen);
            default -> false;
        };
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
     * 位置与总数来自章节确定性题序（Question 粒度），不从大量 attempt 里让前端自己推导。
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
            ChapterPracticeSelector.Sequence sequence = chapters.sequence(learnerId, latest.targetBookId(),
                    latest.targetChapterId(), store.scope(latest.id()));
            count = sequence.size();
            Optional<String> currentQuestionId = store.currentQuestionId(latest.id());
            if (currentQuestionId.isPresent()) {
                int position = sequence.indexOf(currentQuestionId.get());
                index = position < 0 ? null : position + 1;
                if (position >= 0) currentPointId = sequence.steps().get(position).targetKnowledgePointId();
            }
        }
        return new RecentChapterView(active != null ? RECENT_CHAPTER_ACTIVE : RECENT_CHAPTER_LAST,
                active == null ? null : active.id(), latest.id(),
                latest.targetBookId(), names == null ? null : names.bookName(),
                latest.targetChapterId(), names == null ? null : names.chapterName(),
                currentPointId, index, count, latest.updatedAt());
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

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class LearnerPracticeService {
    public record StartRequest(String intent, String targetKnowledgePointId, String sourceQuestionId) {}
    public record SessionView(String id, String intent, String targetKnowledgePointId, String sourceQuestionId,
                              String status, long revision, AttemptView currentAttempt,
                              boolean flowComplete, boolean canRepeat) {}
    public record AttemptView(String id, String status, String targetKnowledgePointId, String targetKnowledgePointName,
                              String evidenceMode, String diagnosisRole, JsonNode question, JsonNode standard,
                              String explanation, String assessment, String gradingSource, boolean answerRevealed) {}

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

    public LearnerPracticeService(LearnerPracticeStore store, QuestionAttemptStore attempts,
                                  StudyProfileService profiles, KnowledgeQuestionPoolService pool,
                                  AdaptiveStudyPlanner planner, LearnerKnowledgeStateService knowledgeStates,
                                  DiagnosticLearningService diagnostics, DiagnosticLearningStore diagnosisStore,
                                  LearnerStore learners, ObjectMapper mapper,
                                  QuestionAttemptVariantService variants,
                                  LearnerQuestionProgressStore questionProgress) {
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
    }

    public List<LearnerPracticeStore.WrongQuestion> wrongQuestions() {
        return store.wrongQuestions(LearnerContext.learnerId());
    }

    @Transactional
    public SessionView start(StartRequest request) {
        String learnerId = LearnerContext.learnerId();
        learners.lockForUpdate(learnerId);
        String intent = request.intent();
        if (!Set.of("knowledge_drill", "wrong_review").contains(intent))
            throw bad("练习类型不合法。");
        var profile = profiles.rawCurrent();
        Set<String> allowed = pool.allowedKnowledgePointIds(new LinkedHashSet<>(profile.selectedBookIds()));
        QuestionAttemptStore.Snapshot source = null;
        String targetId = request.targetKnowledgePointId();
        if ("wrong_review".equals(intent)) {
            if (request.sourceQuestionId() == null) throw bad("请选择要重做的错题。");
            source = attempts.latestWrongForQuestion(learnerId, request.sourceQuestionId())
                    .orElseThrow(() -> bad("这道题已不在待重做队列中。"));
            targetId = source.targetKnowledgePointId();
        }
        requirePlayable(learnerId, targetId, allowed, profile.difficulty());
        String id = UUID.randomUUID().toString();
        store.create(id, learnerId, intent, targetId,
                "wrong_review".equals(intent) ? request.sourceQuestionId() : null, allowed);
        QuestionAttemptStore.Snapshot frozenSource = source;
        String frozenTargetId = targetId;
        PracticeActionContext.within(learnerId, id, () -> {
            String attemptId = frozenSource == null
                    ? draw(id, learnerId, frozenTargetId, allowed, profile.difficulty(), null)
                    : drawExact(id, frozenTargetId, frozenSource);
            store.setCurrentAttempt(id, learnerId, attemptId);
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
    public SessionView answer(String id, String attemptId, String questionId, JsonNode answer) {
        return mutate(id, attemptId, snapshot -> {
            if (!snapshot.questionId().equals(questionId)) throw conflict("题目已经变化，请重新载入。");
            boolean correct = QuestionGradingPolicy.matches(snapshot.standard(), answer);
            Instant occurredAt = Instant.now();
            if (!attempts.recordAnswer(snapshot, answer, correct, occurredAt))
                throw conflict("这道题已经完成评分。");
            diagnostics.handleGradedAttempt(snapshot, correct ? "correct" : "wrong", "automatic", occurredAt,
                    store.scope(id));
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
            diagnostics.handleGradedAttempt(snapshot, assessment, "self", occurredAt, store.scope(id));
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
            if (diagnosis != null && !Set.of("resolved", "abandoned").contains(diagnosis.status())) {
                DiagnosticLearningService.Directive directive = diagnostics.nextDirective(diagnosis.id());
                nextId = draw(id, learnerId, directive.targetKnowledgePointId(), allowed, difficulty, directive);
            } else if (needsTraining(current)) {
                nextId = draw(id, learnerId, session.targetKnowledgePointId(), allowed, difficulty,
                        new DiagnosticLearningService.Directive(null, null, session.targetKnowledgePointId(), "training"));
            } else {
                if ("wrong_review".equals(session.intent()))
                    throw conflict("本轮错题流程已经完成，可以结束练习。");
                nextId = draw(id, learnerId, session.targetKnowledgePointId(), allowed, difficulty, null);
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
                context.readyKnowledgePointIds(), store.seenQuestions(sessionId), preferred,
                training ? KnowledgeQuestionPoolService.Mode.TRAINING : KnowledgeQuestionPoolService.Mode.NORMAL,
                KnowledgeQuestionPoolService.DependencyPolicy.SCOPE_ONLY);
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

    private String drawExact(String sessionId, String targetId, QuestionAttemptStore.Snapshot source) {
        String id = UUID.randomUUID().toString();
        var previous = questionProgress.latestAttemptForQuestion(source.learnerId(), source.questionId()).orElse(null);
        QuestionAttemptVariantService.AttemptVariant variant = variants.create(source.question(), source.standard(),
                previous == null ? source.question() : previous.questionSnapshot(),
                previous == null ? source.standard() : previous.standardAnswer());
        attempts.create(id, null, source.questionId(), variant.question(), variant.standard(),
                source.gradingMode(), targetId, "normal", source.questionDifficulty(), null, null);
        return id;
    }

    private String createAttempt(String learnerId, String targetId, QuestionDto question, String evidenceMode,
                                  DiagnosticLearningService.Directive directive) {
        String id = UUID.randomUUID().toString();
        ObjectNode full = mapper.valueToTree(question);
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
        boolean canRepeat = complete && "knowledge_drill".equals(session.intent()) && hasNext(session);
        return new SessionView(session.id(), session.intent(), session.targetKnowledgePointId(),
                session.sourceQuestionId(), session.status(), session.revision(), attempt, complete,
                canRepeat);
    }

    private AttemptView attemptView(QuestionAttemptStore.Snapshot snapshot) {
        ObjectNode visible = snapshot.question().deepCopy();
        visible.remove(List.of("answer", "aliases", "keywords", "explanation"));
        String targetName = pool.knowledgeDetails(List.of(snapshot.targetKnowledgePointId())).stream()
                .findFirst().map(KnowledgePointDto::name).orElse("当前知识点");
        boolean revealed = Set.of("revealed", "graded").contains(snapshot.status());
        return new AttemptView(snapshot.id(), snapshot.status(), snapshot.targetKnowledgePointId(), targetName,
                snapshot.evidenceMode(), snapshot.diagnosisRole(), visible,
                revealed ? snapshot.standard() : null,
                revealed ? snapshot.question().path("explanation").asText() : null,
                snapshot.assessment(), snapshot.gradingSource(), revealed);
    }

    private boolean flowComplete(LearnerPracticeStore.Session session, QuestionAttemptStore.Snapshot snapshot) {
        if (!"graded".equals(snapshot.status())) return false;
        DiagnosticLearningStore.Session diagnosis = diagnosisForCurrent(session.id(), snapshot);
        if (diagnosis != null) return "resolved".equals(diagnosis.status());
        if ("training".equals(snapshot.evidenceMode())) return "correct".equals(snapshot.assessment());
        return "correct".equals(snapshot.assessment());
    }

    private DiagnosticLearningStore.Session diagnosisForCurrent(
            String practiceSessionId, QuestionAttemptStore.Snapshot snapshot) {
        DiagnosticLearningStore.Session diagnosis = diagnosisStore.latestForPractice(practiceSessionId).orElse(null);
        if (diagnosis == null) return null;
        boolean rootAttempt = snapshot.id().equals(diagnosis.rootAttemptId());
        boolean diagnosisAttempt = diagnosis.id().equals(snapshot.diagnosisSessionId());
        return rootAttempt || diagnosisAttempt ? diagnosis : null;
    }

    private void requirePlayable(String learnerId, String targetId, Set<String> allowed, String difficulty) {
        if (targetId == null || !allowed.contains(targetId)) throw bad("知识点不在当前所选文集范围内。");
        AdaptiveStudyPlanner.QuestionContext context = planner.questionContext(learnerId, allowed, targetId, difficulty);
        var request = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(targetId, allowed,
                context.readyKnowledgePointIds(), Set.of(), context.preferredDifficulty(),
                KnowledgeQuestionPoolService.Mode.NORMAL,
                KnowledgeQuestionPoolService.DependencyPolicy.SCOPE_ONLY);
        if (pool.eligibleQuestionsForLearner(request).isEmpty())
            throw bad("当前知识点暂无可用于专项练习的正式题。");
        if (pool.eligibleKnowledgeDrillQuestions(learnerId, request).isEmpty())
            throw bad("这个知识点当前没有待练的新题，已掌握题目会在复习到期后重新开放。");
    }

    private boolean hasNext(LearnerPracticeStore.Session session) {
        if (!"active".equals(session.status())) return false;
        Set<String> allowed = store.scope(session.id());
        var profile = profiles.rawCurrent();
        AdaptiveStudyPlanner.QuestionContext context = planner.questionContext(
                session.learnerId(), allowed, session.targetKnowledgePointId(), profile.difficulty());
        var request = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(
                session.targetKnowledgePointId(), allowed, context.readyKnowledgePointIds(),
                store.seenQuestions(session.id()), context.preferredDifficulty(),
                KnowledgeQuestionPoolService.Mode.NORMAL,
                KnowledgeQuestionPoolService.DependencyPolicy.SCOPE_ONLY);
        return !pool.eligibleKnowledgeDrillQuestions(session.learnerId(), request).isEmpty();
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
}

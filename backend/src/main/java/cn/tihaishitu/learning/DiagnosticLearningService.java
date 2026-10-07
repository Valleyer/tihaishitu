package cn.tihaishitu.learning;

import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learner.LearnerStore;
import cn.tihaishitu.world.WorldActionContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DiagnosticLearningService {
    public static final String DEPENDENCY_PROBE = "dependency_probe";
    public static final String DEPENDENCY_REMEDIATION = "dependency_remediation";
    public static final String TARGET_RECHECK = "target_recheck";
    public static final String TARGET_REMEDIATION = "target_remediation";

    public record Directive(String diagnosisSessionId, String role, String targetKnowledgePointId,
                            String evidenceMode) {}
    public record GradingResult(String diagnosisSessionId, String diagnosisRole,
                                boolean diagnosisStarted, boolean targetCompleted) {
        public static GradingResult ordinary() { return new GradingResult(null, null, false, false); }
    }

    private final DiagnosticLearningStore store;
    private final QuestionAttemptStore attempts;
    private final LearnerKnowledgeStateService knowledgeStates;
    private final LearnerKnowledgeStateStore stateStore;
    private final LearnerStore learners;
    private final KnowledgeMasteryModel mastery = new KnowledgeMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public DiagnosticLearningService(DiagnosticLearningStore store, QuestionAttemptStore attempts,
                                     LearnerKnowledgeStateService knowledgeStates,
                                     LearnerKnowledgeStateStore stateStore, LearnerStore learners) {
        this.store = store;
        this.attempts = attempts;
        this.knowledgeStates = knowledgeStates;
        this.stateStore = stateStore;
        this.learners = learners;
    }

    public GradingResult handleGradedAttempt(QuestionAttemptStore.Snapshot attempt, String outcome,
                                             String gradingSource, Instant occurredAt,
                                             Set<String> frozenAllowedKnowledgePointIds) {
        if (attempt.learnerId() == null) return GradingResult.ordinary();
        learners.lockForUpdate(attempt.learnerId());
        if (attempt.diagnosisRole() != null) {
            knowledgeStates.apply(attempt, outcome, gradingSource, occurredAt);
            return transitionDiagnosticAttempt(attempt, outcome, occurredAt);
        }
        if (!shouldDiagnose(attempt, outcome)) {
            knowledgeStates.apply(attempt, outcome, gradingSource, occurredAt);
            return GradingResult.ordinary();
        }
        Optional<DiagnosticLearningStore.Session> created = createSession(attempt,
                frozenAllowedKnowledgePointIds, occurredAt);
        if (created.isEmpty()) {
            knowledgeStates.apply(attempt, outcome, gradingSource, occurredAt);
            return GradingResult.ordinary();
        }
        return new GradingResult(created.get().id(), null, true, false);
    }

    public Directive nextDirective(String diagnosisId) {
        DiagnosticLearningStore.Session session = lockCurrentSession(diagnosisId);
        return switch (session.status()) {
            case "diagnosing_dependencies" -> nextDependencyOrAdvance(session);
            case "remediating_dependency" -> {
                DiagnosticLearningStore.Dependency failed = store.dependencies(session.id()).stream()
                        .filter(row -> "failed".equals(row.status())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("诊断会话缺少待补救的前置知识点。"));
                yield new Directive(session.id(), DEPENDENCY_REMEDIATION,
                        failed.knowledgePointId(), "training");
            }
            case "rechecking_target" -> new Directive(session.id(), TARGET_RECHECK,
                    session.targetKnowledgePointId(), "normal");
            case "remediating_target" -> new Directive(session.id(), TARGET_REMEDIATION,
                    session.targetKnowledgePointId(), "training");
            default -> throw new IllegalStateException("诊断会话已经结束。 ");
        };
    }

    public void markProbeUnavailable(String diagnosisId, String pointId) {
        DiagnosticLearningStore.Session session = lockCurrentSession(diagnosisId);
        if (!"diagnosing_dependencies".equals(session.status()))
            throw new IllegalStateException("当前不在前置知识核验阶段。 ");
        store.markUnavailable(diagnosisId, pointId);
    }

    public void abandon(String diagnosisId) {
        if (diagnosisId == null || diagnosisId.isBlank()) return;
        DiagnosticLearningStore.Session session = lockCurrentSession(diagnosisId);
        if (!Set.of("resolved", "abandoned").contains(session.status())) store.abandon(session.id(), clock.instant());
    }

    /**
     * PR3 部署前遗留状态兼容：普通正式训练不再推进诊断链，
     * 因此看到旧的未完成 diagnosisSessionId 时把它标为 abandoned 并继续走普通 selector。
     * 会话不存在（历史数据已清理）时静默跳过，不让旧状态把新流程打成 500。
     */
    public void abandonIfPresent(String diagnosisId) {
        if (diagnosisId == null || diagnosisId.isBlank()) return;
        if (store.find(diagnosisId).isEmpty()) return;
        abandon(diagnosisId);
    }

    private boolean shouldDiagnose(QuestionAttemptStore.Snapshot attempt, String outcome) {
        if (!"normal".equals(attempt.evidenceMode()) || !Set.of("wrong", "partial").contains(outcome)) return false;
        Set<String> ids = new LinkedHashSet<>();
        attempt.question().path("knowledgePointIds").forEach(node -> ids.add(node.asText()));
        ids.remove(attempt.targetKnowledgePointId());
        return !ids.isEmpty();
    }

    private Optional<DiagnosticLearningStore.Session> createSession(QuestionAttemptStore.Snapshot root,
                                                                    Set<String> allowed, Instant now) {
        Optional<DiagnosticLearningStore.Session> existing = store.findByRootAttempt(root.id());
        if (existing.isPresent()) return existing;
        String canonicalTarget = canonicalActive(root.targetKnowledgePointId()).orElse(root.targetKnowledgePointId());
        Map<String, Integer> canonicalDependencies = new LinkedHashMap<>();
        boolean invalidDependency = false;
        int order = 0;
        for (var node : root.question().path("knowledgePointIds")) {
            String raw = node.asText();
            if (raw.equals(root.targetKnowledgePointId())) continue;
            Optional<String> canonical = canonicalActive(raw);
            if (canonical.isEmpty()) {
                invalidDependency = true;
                continue;
            }
            if (!canonical.get().equals(canonicalTarget)) canonicalDependencies.putIfAbsent(canonical.get(), order);
            order++;
        }
        if (canonicalDependencies.isEmpty() && !invalidDependency) return Optional.empty();

        Map<String, KnowledgeMasteryModel.State> states = new HashMap<>();
        stateStore.findForKnowledgePoints(root.learnerId(), canonicalDependencies.keySet())
                .forEach(row -> states.put(row.knowledgePointId(), row.state()));
        List<String> sorted = new ArrayList<>(canonicalDependencies.keySet());
        sorted.sort(Comparator
                .comparingDouble((String id) -> effective(states.get(id), now))
                .thenComparing(id -> lastEvidence(states.get(id)), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(canonicalDependencies::get)
                .thenComparing(Comparator.naturalOrder()));

        String id = UUID.randomUUID().toString();
        boolean unavailable = invalidDependency || sorted.stream().anyMatch(point -> !allowed.contains(point));
        boolean pending = sorted.stream().anyMatch(allowed::contains);
        String status = pending ? "diagnosing_dependencies" : "rechecking_target";
        try {
            store.createSession(id, root.learnerId(), root.worldId(), root.practiceSessionId(), root.id(), canonicalTarget,
                    status, unavailable);
            for (int index = 0; index < sorted.size(); index++) {
                String point = sorted.get(index);
                store.addDependency(id, point, index, allowed.contains(point) ? "pending" : "unavailable");
            }
        } catch (DuplicateKeyException duplicate) {
            return store.findByRootAttempt(root.id());
        }
        return store.find(id);
    }

    private GradingResult transitionDiagnosticAttempt(QuestionAttemptStore.Snapshot attempt,
                                                       String outcome, Instant occurredAt) {
        DiagnosticLearningStore.Session session = lockCurrentSession(attempt.diagnosisSessionId());
        if (!attempt.targetKnowledgePointId().equals(expectedTarget(session, attempt.diagnosisRole())))
            throw new IllegalStateException("诊断题目标与会话状态不一致。 ");
        boolean correct = "correct".equals(outcome);
        switch (attempt.diagnosisRole()) {
            case DEPENDENCY_PROBE -> {
                if (!"diagnosing_dependencies".equals(session.status())) invalidRole();
                store.updateDependency(session.id(), attempt.targetKnowledgePointId(), "pending",
                        correct ? "passed" : "failed");
                if (correct) advanceAfterProbes(store.lock(session.id()));
                else store.transition(session.id(), "diagnosing_dependencies", "remediating_dependency");
            }
            case DEPENDENCY_REMEDIATION -> {
                if (!"remediating_dependency".equals(session.status())) invalidRole();
                if (correct) {
                    store.updateDependency(session.id(), attempt.targetKnowledgePointId(), "failed", "remediated");
                    store.transition(session.id(), "remediating_dependency", "rechecking_target");
                }
            }
            case TARGET_RECHECK -> {
                if (!"rechecking_target".equals(session.status())) invalidRole();
                if (correct) {
                    String resolution = hasRemediated(session.id()) ? "dependency_gap" : "inconclusive";
                    store.resolve(session.id(), "rechecking_target", resolution, occurredAt);
                    return new GradingResult(session.id(), attempt.diagnosisRole(), false, true);
                }
                store.transition(session.id(), "rechecking_target", "remediating_target");
            }
            case TARGET_REMEDIATION -> {
                if (!"remediating_target".equals(session.status())) invalidRole();
                if (correct) {
                    String resolution = hasRemediated(session.id()) ? "mixed_gap"
                            : session.hasUnavailableDependency() ? "inconclusive" : "target_gap";
                    store.resolve(session.id(), "remediating_target", resolution, occurredAt);
                    return new GradingResult(session.id(), attempt.diagnosisRole(), false, true);
                }
            }
            default -> throw new IllegalStateException("未知诊断题角色。 ");
        }
        return new GradingResult(session.id(), attempt.diagnosisRole(), false, false);
    }

    private Directive nextDependencyOrAdvance(DiagnosticLearningStore.Session session) {
        Optional<DiagnosticLearningStore.Dependency> pending = store.dependencies(session.id()).stream()
                .filter(row -> "pending".equals(row.status())).findFirst();
        if (pending.isPresent()) return new Directive(session.id(), DEPENDENCY_PROBE,
                pending.get().knowledgePointId(), "normal");
        advanceAfterProbes(session);
        DiagnosticLearningStore.Session advanced = store.lock(session.id());
        if ("rechecking_target".equals(advanced.status())) return new Directive(advanced.id(), TARGET_RECHECK,
                advanced.targetKnowledgePointId(), "normal");
        return new Directive(advanced.id(), TARGET_REMEDIATION,
                advanced.targetKnowledgePointId(), "training");
    }

    private void advanceAfterProbes(DiagnosticLearningStore.Session session) {
        if (store.dependencies(session.id()).stream().anyMatch(row -> "pending".equals(row.status()))) return;
        session = store.lock(session.id());
        if (session.hasUnavailableDependency()) {
            store.transition(session.id(), "diagnosing_dependencies", "rechecking_target");
            return;
        }
        QuestionAttemptStore.Snapshot root = attempts.findForDiagnosis(session.rootAttemptId(),
                session.learnerId(), session.worldId(), session.practiceSessionId());
        if (!"graded".equals(root.status()) || root.answeredAt() == null)
            throw new IllegalStateException("诊断根答题尚未完成评分。 ");
        knowledgeStates.apply(root, root.assessment(), root.gradingSource(), root.answeredAt());
        store.transition(session.id(), "diagnosing_dependencies", "remediating_target");
    }

    private DiagnosticLearningStore.Session lockCurrentSession(String id) {
        if (id == null) throw new IllegalStateException("诊断题缺少会话标识。 ");
        DiagnosticLearningStore.Session session = store.find(id)
                .orElseThrow(() -> new IllegalStateException("诊断会话不存在。"));
        learners.lockForUpdate(session.learnerId());
        WorldActionContext.Scope world = WorldActionContext.currentOrNull();
        if (world != null && (!world.learnerId().equals(session.learnerId())
                || !world.worldId().equals(session.worldId()) || session.practiceSessionId() != null))
            throw new IllegalStateException("诊断会话不属于当前学习者或世界。 ");
        PracticeActionContext.Scope practice = PracticeActionContext.currentOrNull();
        if (practice != null && (!practice.learnerId().equals(session.learnerId())
                || !practice.practiceSessionId().equals(session.practiceSessionId())
                || session.worldId() != null))
            throw new IllegalStateException("诊断会话不属于当前学习者或专项练习。 ");
        if (world == null && practice == null)
            throw new IllegalStateException("诊断会话缺少受验证的执行上下文。 ");
        return store.lock(id);
    }

    private String expectedTarget(DiagnosticLearningStore.Session session, String role) {
        if (DEPENDENCY_PROBE.equals(role) || DEPENDENCY_REMEDIATION.equals(role)) {
            String expected = "failed";
            if (DEPENDENCY_PROBE.equals(role)) expected = "pending";
            String status = expected;
            return store.dependencies(session.id()).stream().filter(row -> status.equals(row.status()))
                    .findFirst().map(DiagnosticLearningStore.Dependency::knowledgePointId)
                    .orElseThrow(() -> new IllegalStateException("诊断依赖状态不一致。"));
        }
        return session.targetKnowledgePointId();
    }

    private Optional<String> canonicalActive(String pointId) {
        if (pointId == null) return Optional.empty();
        Set<String> visited = new LinkedHashSet<>();
        String current = pointId;
        while (visited.add(current)) {
            Optional<DiagnosticLearningStore.CanonicalPoint> point = store.point(current);
            if (point.isEmpty()) return Optional.empty();
            if ("active".equals(point.get().status()) && point.get().mergedIntoId() == null)
                return Optional.of(current);
            if (point.get().mergedIntoId() == null) return Optional.empty();
            current = point.get().mergedIntoId();
        }
        return Optional.empty();
    }

    private double effective(KnowledgeMasteryModel.State state, Instant now) {
        return state == null ? 0d : mastery.effectiveMastery(state, now);
    }
    private static Instant lastEvidence(KnowledgeMasteryModel.State state) {
        return state == null ? null : state.lastEvidenceAt();
    }
    private boolean hasRemediated(String id) {
        return store.dependencies(id).stream().anyMatch(row -> "remediated".equals(row.status()));
    }
    private static void invalidRole() { throw new IllegalStateException("诊断题角色与会话状态不一致。 "); }
}

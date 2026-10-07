package cn.tihaishitu.game;

import cn.tihaishitu.catalog.KnowledgePointDto;
import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.learner.StudyProfileService;
import cn.tihaishitu.learning.AdaptiveStudyPlanner;
import cn.tihaishitu.learning.DiagnosticLearningService;
import cn.tihaishitu.learning.LearnerKnowledgeStateService;
import cn.tihaishitu.world.WorldActionContext;
import cn.tihaishitu.world.WorldStateStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class GameActionService {
    private final GameStore games;
    private final QuestionAttemptStore attempts;
    private final KnowledgeQuestionPoolService questionPool;
    private final GameContent content;
    private final GameFactory factory;
    private final ObjectMapper mapper;
    private final WorldStateStore worldStates;
    private final StudyProfileService studyProfiles;
    private final LearnerKnowledgeStateService knowledgeStates;
    private final AdaptiveStudyPlanner adaptivePlanner;
    private final DiagnosticLearningService diagnostics;
    private final cn.tihaishitu.learning.QuestionExamMetadataBuilder examMetadataBuilder;

    public GameActionService(GameStore games, QuestionAttemptStore attempts,
                             KnowledgeQuestionPoolService questionPool,
                             GameContent content, GameFactory factory, ObjectMapper mapper,
                             WorldStateStore worldStates, StudyProfileService studyProfiles,
                             LearnerKnowledgeStateService knowledgeStates,
                             AdaptiveStudyPlanner adaptivePlanner,
                             DiagnosticLearningService diagnostics,
                             cn.tihaishitu.learning.QuestionExamMetadataBuilder examMetadataBuilder) {
        this.games = games;
        this.attempts = attempts;
        this.questionPool = questionPool;
        this.content = content;
        this.factory = factory;
        this.mapper = mapper;
        this.worldStates = worldStates;
        this.studyProfiles = studyProfiles;
        this.knowledgeStates = knowledgeStates;
        this.adaptivePlanner = adaptivePlanner;
        this.diagnostics = diagnostics;
        this.examMetadataBuilder = examMetadataBuilder;
    }

    @Transactional
    public ObjectNode travel(String gameId, String locationId) {
        ObjectNode game = game(gameId);
        ObjectNode location = content.location(locationId)
                .orElseThrow(() -> bad("这个地点尚未开放。"));
        ensureNoActiveRun(game);
        assertRequirements(game, location.path("requirements"));
        ObjectNode adventure = adventure(game);
        adventure.put("locationId", locationId);
        addUnique(adventure.withArray("visited"), locationId);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode talk(String gameId, String npcId, String topicId) {
        ObjectNode game = game(gameId);
        ObjectNode companion = content.companion(npcId)
                .orElseThrow(() -> bad("此人眼下无话可谈。"));
        if (!adventure(game).path("locationId").asText().equals(companion.path("locationId").asText()))
            throw bad("请先去此人所在的地方。 ");
        JsonNode topic = find(companion.path("topics"), topicId)
                .orElseThrow(() -> bad("这个话题尚未开启。"));
        ObjectNode npc = npc(game, npcId);
        if (npc.path("favorability").asInt() < topic.path("minFavorability").asInt()) throw bad("还需要一些共同经历。");
        npc.put("met", true);
        ObjectNode conversations = adventure(game).with("conversations");
        conversations.put(npcId + ":" + topicId, conversations.path(npcId + ":" + topicId).asInt() + 1);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode registerExam(String gameId, String examId) {
        ObjectNode game = game(gameId);
        ObjectNode exam = content.exam(examId).orElseThrow(() -> bad("这场考试尚未开放。"));
        ensureNoActiveRun(game);
        if (!adventure(game).path("locationId").asText().equals(exam.path("locationId").asText()))
            throw bad("请先到试院前巷报名。");
        ObjectNode record = (ObjectNode) adventure(game).with("exams").path(examId);
        if (!"unregistered".equals(record.path("status").asText())) throw bad("名帖已经递入试院。");
        assertRequirements(game, exam.path("requirements"));
        record.put("status", "registered");
        journal(game, exam.path("name").asText() + " · 投递名帖", "名帖已经验明，你已取得本场应试资格。开考时才暂收报名银。");
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode beginActivity(String gameId, String activityId) {
        ObjectNode game = game(gameId);
        ObjectNode activity = content.activity(activityId)
                .orElseThrow(() -> bad("这项活动暂不可用。"));
        ensureNoActiveRun(game);
        ObjectNode state = adventure(game);
        boolean task = "task".equals(activity.path("activityMode").asText());
        if (task && state.path("clears").path(activityId).asInt() > 0)
            throw bad("这项任务已经完成。 ");
        if (activity.hasNonNull("locationId") &&
                !adventure(game).path("locationId").asText().equals(activity.path("locationId").asText()))
            throw bad("请先前往活动所在地点。");
        assertRequirements(game, activity.path("requirements"));
        content.examForActivity(activityId).ifPresent(exam -> {
            String status = adventure(game).path("exams").path(exam.path("id").asText()).path("status").asText();
            if (activityId.equals(exam.path("activityId").asText()) && !"registered".equals(status))
                throw bad("须先取得本场应试资格。");
        });
        int entryCost = task ? content.examForActivity(activityId)
                .filter(exam -> activityId.equals(exam.path("activityId").asText()))
                .map(exam -> exam.path("fee").asInt())
                .orElse(activity.path("entryCost").asInt()) : 0;
        ObjectNode player = (ObjectNode) game.path("player");
        if (player.path("coins").asInt() < entryCost) throw bad("入场银两不足。");
        player.put("coins", player.path("coins").asInt() - entryCost);
        int rounds = activity.path("rounds").asInt(5);
        KnowledgeQuestionPoolService.StudyPlan plan = WorldActionContext.active()
                ? studyProfiles.plan(rounds)
                : questionPool.planKnowledgePoints(selectedBookIds(game), rounds);
        ObjectNode run = mapper.createObjectNode();
        run.put("id", UUID.randomUUID().toString());
        run.set("definition", activity.deepCopy());
        run.put("answered", 0);
        run.put("correct", 0);
        run.set("knowledgePointIds", mapper.valueToTree(plan.knowledgePointIds()));
        // Freeze the selected Book boundary; formal draws recompute readiness inside this scope.
        run.set("allowedKnowledgePointIds", mapper.valueToTree(plan.allowedKnowledgePointIds()));
        run.put("knowledgePointIndex", 0);
        run.put("training", false);
        run.put("trainingAnswered", 0);
        run.put("diagnosticAnswered", 0);
        run.putNull("diagnosisSessionId");
        run.set("seenQuestionIds", mapper.createArrayNode());
        run.put("status", "active");
        run.put("score", 0);
        run.put("grade", "");
        run.set("rewards", mapper.createArrayNode());
        run.put("response", "");
        run.put("entryCost", entryCost);
        run.put("costCommitted", false);
        run.put("costRefunded", false);
        adventure(game).set("run", run);
        adventure(game).putNull("encounter");
        drawAttempt(game, run);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode answer(String gameId, AnswerRequest request) {
        knowledgeStates.lockCurrentLearnerForGrading();
        ObjectNode game = game(gameId);
        ObjectNode current = requireCurrentAttempt(game, request.attemptId());
        String currentQuestionId = current.path("question").path("id").asText();
        if (!currentQuestionId.equals(request.questionId())) throw bad("题目已经变化，请重新载入。");
        QuestionAttemptStore.Snapshot snapshot = attempts.find(request.attemptId(), gameId);
        if (!snapshot.questionId().equals(request.questionId())) throw bad("题目与课卷不匹配。");
        boolean correct = QuestionGradingPolicy.matches(snapshot.standard(), request.answer());
        Instant occurredAt = Instant.now();
        if (!attempts.recordAnswer(snapshot, request.answer(), correct, occurredAt)) return game;
        DiagnosticLearningService.GradingResult diagnosis = diagnostics.handleGradedAttempt(snapshot,
                correct ? "correct" : "wrong", "automatic", occurredAt,
                frozenAllowedKnowledgePointIds(activeRun(game)));

        ObjectNode result = mapper.createObjectNode();
        result.put("correct", correct);
        result.set("answer", request.answer().deepCopy());
        result.set("standard", snapshot.standard().deepCopy());
        result.put("explanation", snapshot.question().path("explanation").asText());
        result.set("aliases", snapshot.question().path("aliases").deepCopy());
        result.put("story", story(correct, diagnosis));
        result.set("changes", mapper.createArrayNode());
        current.set("result", result);

        ObjectNode record = mapper.createObjectNode();
        record.put("attemptId", request.attemptId());
        record.put("questionId", request.questionId());
        record.set("answer", request.answer().deepCopy());
        record.put("correct", correct);
        record.put("at", Instant.now().toString());
        record.put("review", current.path("review").asBoolean());
        game.withArray("records").add(record);
        updateLearning(game, request.questionId(), request.answer(), correct);
        settleRunAnswer(game, correct, request.questionId(), diagnosis);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode reveal(String gameId, String attemptId, String questionId) {
        ObjectNode game = game(gameId);
        ObjectNode current = requireCurrentAttempt(game, attemptId);
        if (!questionId.equals(current.path("question").path("id").asText()))
            throw bad("题目已经变化，请重新载入。");
        QuestionAttemptStore.Snapshot snapshot = attempts.find(attemptId, gameId);
        if (!snapshot.questionId().equals(questionId)) throw bad("题目与课卷不匹配。");
        if (current.has("reveal") && !current.path("reveal").isNull()) return game;
        attempts.reveal(snapshot);

        ObjectNode reveal = mapper.createObjectNode();
        reveal.set("standard", snapshot.standard().deepCopy());
        reveal.put("explanation", snapshot.question().path("explanation").asText());
        reveal.set("knowledgePoints", knowledgeDetails(snapshot.question()));
        current.set("reveal", reveal);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode selfAssess(String gameId, SelfAssessmentRequest request) {
        knowledgeStates.lockCurrentLearnerForGrading();
        ObjectNode game = game(gameId);
        ObjectNode current = requireCurrentAttempt(game, request.attemptId());
        if (!request.questionId().equals(current.path("question").path("id").asText()))
            throw bad("题目已经变化，请重新载入。");
        QuestionAttemptStore.Snapshot snapshot = attempts.find(request.attemptId(), gameId);
        if (!snapshot.questionId().equals(request.questionId())) throw bad("题目与课卷不匹配。");
        if (!current.path("result").isNull()) return game;
        Instant occurredAt = Instant.now();
        if (!attempts.recordSelfAssessment(snapshot, request.assessment(), occurredAt)) {
            throw bad("请先查看参考解答，或此题已经完成自评。");
        }
        DiagnosticLearningService.GradingResult diagnosis = diagnostics.handleGradedAttempt(snapshot,
                request.assessment(), "self", occurredAt, frozenAllowedKnowledgePointIds(activeRun(game)));
        boolean correct = "correct".equals(request.assessment());
        ObjectNode result = mapper.createObjectNode();
        result.put("correct", correct);
        result.put("assessment", request.assessment());
        result.put("gradingSource", "self");
        result.put("answer", request.assessment());
        result.set("standard", snapshot.standard().deepCopy());
        result.put("explanation", snapshot.question().path("explanation").asText());
        result.set("aliases", snapshot.question().path("aliases").deepCopy());
        result.put("story", diagnosis.diagnosisStarted()
                ? "此题牵涉前置知识，先查根问底，再决定错处归因。"
                : switch (request.assessment()) {
            case "correct" -> "自校无误，此题已经完整掌握。";
            case "partial" -> "思路已有根基，尚有步骤需要补全。";
            default -> "错处已经记下，接下来会从同一知识点查漏补缺。";
        });
        result.set("changes", mapper.createArrayNode());
        current.set("result", result);

        ObjectNode record = mapper.createObjectNode();
        record.put("attemptId", request.attemptId());
        record.put("questionId", request.questionId());
        record.put("answer", request.assessment());
        record.put("correct", correct);
        record.put("assessment", request.assessment());
        record.put("gradingSource", "self");
        record.put("at", Instant.now().toString());
        record.put("review", current.path("review").asBoolean());
        game.withArray("records").add(record);
        updateLearning(game, request.questionId(), mapper.getNodeFactory().textNode(request.assessment()), correct);
        ObjectNode learning = (ObjectNode) game.with("learning").path(request.questionId());
        if ("partial".equals(request.assessment()))
            learning.put("partial", learning.path("partial").asInt() + 1);
        settleRunAnswer(game, correct, request.questionId(), diagnosis);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode next(String gameId, NextQuestionRequest request) {
        knowledgeStates.lockCurrentLearnerForGrading();
        ObjectNode game = game(gameId);
        ObjectNode current = requireCurrentAttempt(game, request.attemptId());
        if (current.path("result").isNull()) throw bad("请先完成当前题目。");
        ObjectNode run = activeRun(game);
        if (!"active".equals(run.path("status").asText())) throw bad("本轮已经完成，请先领取结算。");
        drawAttempt(game, run);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode finish(String gameId, String runId) {
        ObjectNode game = game(gameId);
        ObjectNode run = (ObjectNode) adventure(game).path("run");
        if (run.isMissingNode() || run.isNull() || !runId.equals(run.path("id").asText())) throw bad("行程已变化。");
        if (!"settled".equals(run.path("status").asText())) throw bad("这段行程尚未结算。");
        adventure(game).putNull("run");
        game.putNull("attempt");
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode abandon(String gameId, String runId) {
        knowledgeStates.lockCurrentLearnerForGrading();
        ObjectNode game = game(gameId);
        JsonNode run = adventure(game).path("run");
        if (run.isMissingNode() || run.isNull() || !runId.equals(run.path("id").asText())) throw bad("行程已变化。");
        if (WorldActionContext.active() && run.hasNonNull("diagnosisSessionId"))
            diagnostics.abandon(run.path("diagnosisSessionId").asText());
        refundEscrow(game, (ObjectNode) run);
        adventure(game).putNull("run");
        game.putNull("attempt");
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode saveNote(String gameId, String questionId, String note) {
        if (note.length() > 4000) throw bad("每题批注最多 4000 字。");
        ObjectNode game = game(gameId);
        game.with("notes").put(questionId, note);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode configure(String gameId, JsonNode body) {
        ObjectNode game = game(gameId);
        game.with("config").set("bankIds", body.path("bankIds").deepCopy());
        game.with("config").set("weights", body.path("weights").deepCopy());
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode acknowledgeChapter(String gameId, String chapterId) {
        ObjectNode game = game(gameId);
        addUnique(game.withArray("flags"), "chapter-intro:" + chapterId);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode dismissEncounter(String gameId) {
        ObjectNode game = game(gameId);
        adventure(game).putNull("encounter");
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode useItem(String gameId, String itemId) {
        ObjectNode game = game(gameId);
        ObjectNode item = content.item(itemId).orElseThrow(() -> bad("没有找到这件物品。"));
        ObjectNode state = adventure(game), inventory = state.with("inventory");
        if (inventory.path(itemId).asInt() <= 0) throw bad("行囊中没有这件物品。");
        if ("consumable".equals(item.path("kind").asText())) {
            grantRewards(game, item.path("use"));
            inventory.put(itemId, inventory.path(itemId).asInt() - 1);
        } else if (item.hasNonNull("slot")) state.with("equipped").put(item.path("slot").asText(), itemId);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode buyItem(String gameId, String itemId) {
        ObjectNode game = game(gameId);
        ObjectNode item = content.item(itemId).orElseThrow(() -> bad("没有找到这件物品。"));
        int price = item.path("price").asInt(-1);
        if (price < 0) throw bad("这件物品不能购买。");
        ObjectNode player = (ObjectNode) game.path("player");
        if (player.path("coins").asInt() < price) throw bad("银两不足。");
        player.put("coins", player.path("coins").asInt() - price);
        ObjectNode inventory = adventure(game).with("inventory");
        inventory.put(itemId, inventory.path(itemId).asInt() + 1);
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode claimBond(String gameId, String npcId, int milestoneIndex) {
        ObjectNode game = game(gameId);
        ObjectNode companion = content.companion(npcId).orElseThrow(() -> bad("此人尚无可领取的心意。"));
        JsonNode milestone = companion.path("milestones").path(milestoneIndex);
        if (milestone.isMissingNode()) throw bad("这份心意尚不存在。");
        if (!adventure(game).path("locationId").asText().equals(companion.path("locationId").asText()))
            throw bad("去当面领取这份心意吧。");
        if (npc(game, npcId).path("favorability").asInt() < milestone.path("favorability").asInt())
            throw bad("还需要一些共同经历。");
        String key = "bond:" + npcId + ":" + milestoneIndex;
        ArrayNode claims = adventure(game).withArray("rewardClaims");
        for (JsonNode value : claims) if (key.equals(value.asText())) return game;
        grantRewards(game, milestone.path("reward"));
        claims.add(key);
        journal(game, milestone.path("title").asText("故人心意"),
                npc(game, npcId).path("name").asText() + "：" + milestone.path("dialogue").asText());
        persist(game);
        return game;
    }

    @Transactional
    public ObjectNode choose(String gameId, String eventId, String choiceId) {
        ObjectNode game = game(gameId);
        if (!eventId.equals(game.path("event").path("id").asText())) throw bad("这段际遇已经变化。");
        ObjectNode event = content.event(eventId).orElseThrow(() -> bad("这段际遇不存在。"));
        JsonNode choice = find(event.path("options"), choiceId).orElseThrow(() -> bad("这个选择不存在。"));
        grantRewards(game, choice.path("effects"));
        journal(game, event.path("title").asText("途中际遇"), choice.path("text").asText());
        game.putNull("event");
        persist(game);
        return game;
    }

    private void drawAttempt(ObjectNode game, ObjectNode run) {
        Set<String> seen = new HashSet<>();
        run.path("seenQuestionIds").forEach(id -> seen.add(id.asText()));
        Set<String> allowed = allowedKnowledgePointIds(game, run);
        WorldActionContext.Scope world = WorldActionContext.currentOrNull();
        DiagnosticLearningService.Directive directive = null;
        String pointId;
        KnowledgeQuestionPoolService.Mode mode;
        QuestionDto question;
        if (world != null && run.hasNonNull("diagnosisSessionId")) {
            while (true) {
                directive = diagnostics.nextDirective(run.path("diagnosisSessionId").asText());
                pointId = directive.targetKnowledgePointId();
                mode = "training".equals(directive.evidenceMode())
                        ? KnowledgeQuestionPoolService.Mode.TRAINING : KnowledgeQuestionPoolService.Mode.NORMAL;
                var profile = studyProfiles.rawCurrent();
                AdaptiveStudyPlanner.QuestionContext context = adaptivePlanner.questionContext(
                        world.learnerId(), allowed, pointId, profile.difficulty());
                int preferred = DiagnosticLearningService.DEPENDENCY_PROBE.equals(directive.role())
                        ? Math.min(3, context.preferredDifficulty()) : context.preferredDifficulty();
                var request = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(pointId, allowed,
                        seen, preferred, mode);
                if (DiagnosticLearningService.DEPENDENCY_PROBE.equals(directive.role())
                        && questionPool.eligibleQuestionsForLearner(request).isEmpty()) {
                    diagnostics.markProbeUnavailable(directive.diagnosisSessionId(), pointId);
                    continue;
                }
                if (!DiagnosticLearningService.DEPENDENCY_PROBE.equals(directive.role())
                        && questionPool.eligibleQuestionsForLearner(request).isEmpty()) {
                    // 知识点的正式题可能只有 root 一道。核验 / 补救必须针对该题重新发卷，
                    // 因此这一种诊断题允许复用本轮已见题，而不是让整轮诊断失效。
                    request = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(pointId, allowed,
                            Set.of(), preferred, mode);
                }
                question = questionPool.selectQuestionForLearner(world.learnerId(), request);
                break;
            }
        } else {
            int index = run.path("knowledgePointIndex").asInt();
            pointId = run.path("knowledgePointIds").path(index).asText();
            mode = run.path("training").asBoolean()
                    ? KnowledgeQuestionPoolService.Mode.TRAINING : KnowledgeQuestionPoolService.Mode.NORMAL;
            if (world == null) {
            question = questionPool.selectQuestion(new KnowledgeQuestionPoolService.QuestionPoolRequest(
                    pointId, allowed, seen, null, mode));
            } else {
                var profile = studyProfiles.rawCurrent();
                AdaptiveStudyPlanner.QuestionContext context = adaptivePlanner.questionContext(
                        world.learnerId(), allowed, pointId, profile.difficulty());
                question = questionPool.selectQuestionForLearner(world.learnerId(),
                        new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(pointId, allowed,
                                seen, context.preferredDifficulty(), mode));
            }
        }
        String attemptId = UUID.randomUUID().toString();
        ObjectNode full = mapper.valueToTree(question);
        // 与 Learning Hub Practice 共用同一个 metadata builder：来源 / 年份 / 原始题号 /
        // displayQuestionNumber / examLabel / 全部 core+auxiliary KP 标签，
        // 一并冻结进 attempt snapshot，World 题面刷新后不会变化。
        full.set("examMetadata", examMetadataBuilder.build(question.id(), question.chapter()));
        ObjectNode visible = full.deepCopy();
        visible.remove(List.of("answer", "aliases", "keywords", "explanation"));
        ArrayNode knowledge = mapper.createArrayNode();
        List<KnowledgePointDto> details = questionPool.knowledgeDetails(question.knowledgePointIds());
        details.forEach(point -> {
            ObjectNode visiblePoint = mapper.valueToTree(point);
            if ("self_assessment".equals(full.path("gradingMode").asText("auto"))) {
                visiblePoint.put("description", "");
                visiblePoint.put("explanation", "");
            }
            knowledge.add(visiblePoint);
        });
        visible.set("knowledgePoints", knowledge);
        ObjectNode attempt = mapper.createObjectNode();
        attempt.put("id", attemptId);
        String selectedPointId = pointId;
        KnowledgePointDto target = details.stream().filter(point -> selectedPointId.equals(point.id())).findFirst()
                .orElseThrow(() -> bad("当前修习知识点已经停用或不存在。"));
        attempt.put("targetKnowledgePointId", target.id());
        attempt.put("targetKnowledgePointName", target.name());
        if (directive == null) attempt.putNull("learningPurpose");
        else attempt.put("learningPurpose", learningPurpose(directive.role()));
        attempt.set("question", visible);
        attempt.set("scene", scene(question, run.path("definition")));
        attempt.putNull("result");
        attempt.putNull("reveal");
        boolean remediation = directive == null ? run.path("training").asBoolean()
                : "training".equals(directive.evidenceMode());
        run.put("training", remediation);
        attempt.put("review", remediation);
        game.set("attempt", attempt);
        attempts.create(attemptId, game.path("id").asText(), question.id(), full, question.answer(),
                full.path("gradingMode").asText("auto"), target.id(),
                remediation ? "training" : "normal", question.difficulty(),
                directive == null ? null : directive.diagnosisSessionId(),
                directive == null ? null : directive.role());
    }

    private ArrayNode knowledgeDetails(JsonNode question) {
        Set<String> ids = new HashSet<>();
        question.path("knowledgePointIds").forEach(id -> ids.add(id.asText()));
        ArrayNode result = mapper.createArrayNode();
        questionPool.knowledgeDetails(ids).forEach(point -> result.add(mapper.valueToTree(point)));
        return result;
    }

    private ObjectNode scene(QuestionDto question, JsonNode activity) {
        ObjectNode scene = mapper.createObjectNode();
        scene.put("id", UUID.randomUUID().toString());
        scene.put("title", activity.path("name").asText("案头考校"));
        scene.put("location", activity.path("locationId").asText("青溪"));
        scene.put("speaker", "司卷人");
        scene.put("role", "考校");
        scene.put("text", activity.path("description").asText());
        scene.put("dialogue", activity.path("invitation").asText("请看此题。"));
        scene.put("task", question.subject() + " · " + question.chapter());
        scene.put("npcId", activity.path("npcId").asText(""));
        scene.put("success", "答得不错。");
        scene.put("failure", "错处记下，再从根处查起。");
        return scene;
    }

    private Set<String> selectedBookIds(ObjectNode game) {
        if (WorldActionContext.active()) return new LinkedHashSet<>(studyProfiles.rawCurrent().selectedBookIds());
        Set<String> selected = new LinkedHashSet<>();
        game.path("config").path("bankIds").forEach(id -> selected.add(id.asText()));
        return selected;
    }

    private Set<String> allowedKnowledgePointIds(ObjectNode game, ObjectNode run) {
        Set<String> allowed = new LinkedHashSet<>();
        run.path("allowedKnowledgePointIds").forEach(id -> allowed.add(id.asText()));
        if (!allowed.isEmpty()) return allowed;
        allowed.addAll(questionPool.allowedKnowledgePointIds(selectedBookIds(game)));
        run.set("allowedKnowledgePointIds", mapper.valueToTree(allowed));
        return allowed;
    }

    private static Set<String> frozenAllowedKnowledgePointIds(ObjectNode run) {
        Set<String> allowed = new LinkedHashSet<>();
        run.path("allowedKnowledgePointIds").forEach(id -> allowed.add(id.asText()));
        return allowed;
    }

    private void settleRunAnswer(ObjectNode game, boolean correct, String questionId,
                                 DiagnosticLearningService.GradingResult diagnosis) {
        ObjectNode run = activeRun(game);
        run.put("answered", run.path("answered").asInt() + 1);
        addUnique(run.withArray("seenQuestionIds"), questionId);
        if (diagnosis.diagnosisStarted()) {
            run.put("diagnosisSessionId", diagnosis.diagnosisSessionId());
            run.put("training", false);
            return;
        }
        if (diagnosis.diagnosisRole() != null) {
            if (Set.of(DiagnosticLearningService.DEPENDENCY_PROBE,
                    DiagnosticLearningService.TARGET_RECHECK).contains(diagnosis.diagnosisRole()))
                run.put("diagnosticAnswered", run.path("diagnosticAnswered").asInt() + 1);
            else run.put("trainingAnswered", run.path("trainingAnswered").asInt() + 1);
            if (diagnosis.targetCompleted()) {
                run.putNull("diagnosisSessionId");
                run.put("training", false);
                run.put("knowledgePointIndex", run.path("knowledgePointIndex").asInt() + 1);
                settleRunIfComplete(game, run);
            }
            return;
        }
        if (run.path("training").asBoolean()) {
            run.put("trainingAnswered", run.path("trainingAnswered").asInt() + 1);
            if (correct) {
                run.put("training", false);
                run.put("knowledgePointIndex", run.path("knowledgePointIndex").asInt() + 1);
            }
        } else if (correct) {
            run.put("correct", run.path("correct").asInt() + 1);
            run.put("knowledgePointIndex", run.path("knowledgePointIndex").asInt() + 1);
        } else {
            run.put("training", true);
        }
        settleRunIfComplete(game, run);
    }

    private void settleRunIfComplete(ObjectNode game, ObjectNode run) {
        if (run.path("knowledgePointIndex").asInt() < run.path("knowledgePointIds").size()) return;
        int score = Math.round(run.path("correct").asInt() * 100f / run.path("knowledgePointIds").size());
        run.put("score", score);
        run.put("status", "settled");
        boolean task = "task".equals(run.path("definition").path("activityMode").asText());
        boolean completed = score >= run.path("definition").path("passScore").asInt();
        JsonNode selected = mapper.missingNode();
        for (JsonNode tier : run.path("definition").path("tiers"))
            if (tier.path("minScore").asInt() <= score &&
                    (selected.isMissingNode() || tier.path("minScore").asInt() > selected.path("minScore").asInt())) selected = tier;
        run.put("grade", selected.path("label").asText(score >= 60 ? "过关" : "未过关"));
        run.put("response", task
                ? run.path("definition").path(completed ? "successDialogue" : "failureDialogue").asText()
                : selected.path("dialogue").asText());
        ArrayNode rewards = mapper.createArrayNode();
        String activityId = run.path("definition").path("id").asText();
        ObjectNode bestScores = adventure(game).with("best");
        bestScores.put(activityId, Math.max(bestScores.path(activityId).asInt(), score));
        ObjectNode clears = adventure(game).with("clears");
        if (task) {
            if (completed) {
                if (clears.path(activityId).asInt() == 0)
                    grantRewards(game, run.path("definition").path("completionReward"), rewards);
                clears.put(activityId, 1);
                run.put("costCommitted", true);
            } else refundEscrow(game, run);
        } else {
            grantRewards(game, selected.path("rewards"), rewards);
        }
        if (!task && completed) {
            boolean first = clears.path(activityId).asInt() == 0;
            clears.put(activityId, clears.path(activityId).asInt() + 1);
            if (first) grantRewards(game, selected.path("firstRewards"), rewards);
        }
        run.set("rewards", rewards);
        content.examForActivity(activityId).ifPresent(exam -> {
            if (!activityId.equals(exam.path("activityId").asText())) return;
            ObjectNode record = (ObjectNode) adventure(game).with("exams").path(exam.path("id").asText());
            record.put("best", Math.max(record.path("best").asInt(), score));
            if (task) {
                if (completed) record.put("status", "passed");
                else if (!"passed".equals(record.path("status").asText())) record.put("status", "registered");
            } else {
                record.put("attempts", record.path("attempts").asInt() + 1);
                record.put("lastScore", score);
                if (completed) record.put("status", "passed");
            }
        });
    }

    private void refundEscrow(ObjectNode game, ObjectNode run) {
        int entryCost = run.path("entryCost").asInt();
        if (entryCost <= 0 || run.path("costCommitted").asBoolean() || run.path("costRefunded").asBoolean()) return;
        ObjectNode player = (ObjectNode) game.path("player");
        player.put("coins", player.path("coins").asInt() + entryCost);
        run.put("costRefunded", true);
    }

    private static String story(boolean correct, DiagnosticLearningService.GradingResult diagnosis) {
        if (diagnosis.diagnosisStarted()) return "此题牵涉前置知识，先查根问底，再决定错处归因。";
        return correct ? "此题已解，卷上添了一笔笃定。" : "错处已经记下，接下来会从同一知识点查漏补缺。";
    }

    private static String learningPurpose(String role) {
        return switch (role) {
            case DiagnosticLearningService.DEPENDENCY_PROBE -> "查根问底";
            case DiagnosticLearningService.DEPENDENCY_REMEDIATION -> "补基础";
            case DiagnosticLearningService.TARGET_RECHECK -> "回卷再试";
            case DiagnosticLearningService.TARGET_REMEDIATION -> "温故补缺";
            default -> "";
        };
    }

    private void updateLearning(ObjectNode game, String questionId, JsonNode answer, boolean correct) {
        ObjectNode learning = game.with("learning");
        ObjectNode item = learning.has(questionId) ? (ObjectNode) learning.path(questionId) : mapper.createObjectNode();
        int total = item.path("attempts").asInt() + 1;
        int right = item.path("correct").asInt() + (correct ? 1 : 0);
        int wrong = item.path("wrong").asInt() + (correct ? 0 : 1);
        item.put("attempts", total); item.put("correct", right); item.put("wrong", wrong);
        item.put("errorRate", Math.round(wrong * 100f / total));
        item.put("streak", correct ? item.path("streak").asInt() + 1 : 0);
        item.put("lastIndex", game.path("records").size() - 1);
        item.put("dueAt", game.path("records").size() + (correct ? 20 : 5));
        item.put("lastAt", Instant.now().toString());
        ArrayNode wrongAnswers = item.has("wrongAnswers") ? (ArrayNode) item.path("wrongAnswers") : mapper.createArrayNode();
        if (!correct) wrongAnswers.add(answer.deepCopy());
        item.set("wrongAnswers", wrongAnswers); item.put("reviewCount", item.path("reviewCount").asInt());
        learning.set(questionId, item);
    }

    private void grantRewards(ObjectNode game, JsonNode reward) { grantRewards(game, reward, null); }
    private void grantRewards(ObjectNode game, JsonNode reward, ArrayNode lines) {
        if (reward == null || reward.isMissingNode() || reward.isNull()) return;
        ObjectNode player = (ObjectNode) game.path("player");
        for (String key : List.of("knowledge", "coins", "reputation")) if (reward.path(key).asInt() != 0) {
            player.put(key, Math.max(0, player.path(key).asInt() + reward.path(key).asInt()));
            if (lines != null) lines.add(key + " +" + reward.path(key).asInt());
        }
        ObjectNode attrs = adventure(game).with("attributes");
        reward.path("attributes").fields().forEachRemaining(entry -> attrs.put(entry.getKey(), attrs.path(entry.getKey()).asInt() + entry.getValue().asInt()));
        ObjectNode inventory = adventure(game).with("inventory");
        reward.path("items").fields().forEachRemaining(entry -> inventory.put(entry.getKey(), inventory.path(entry.getKey()).asInt() + entry.getValue().asInt()));
        reward.path("favorability").fields().forEachRemaining(entry -> {
            ObjectNode npc = npc(game, entry.getKey());
            npc.put("met", true);
            npc.put("favorability", Math.max(0, Math.min(100,
                    npc.path("favorability").asInt() + entry.getValue().asInt())));
        });
        reward.path("flags").forEach(flag -> addUnique(game.withArray("flags"), flag.asText()));
        if (reward.hasNonNull("title")) player.put("title", reward.path("title").asText());
    }

    private void assertRequirements(ObjectNode game, JsonNode requirements) {
        if (game.path("player").path("knowledge").asInt() < requirements.path("knowledge").asInt()) throw bad("学识尚未达到要求。");
        if (game.path("player").path("reputation").asInt() < requirements.path("reputation").asInt()) throw bad("声望尚未达到要求。");
        requirements.path("attributes").fields().forEachRemaining(entry -> {
            if (adventure(game).path("attributes").path(entry.getKey()).asInt() < entry.getValue().asInt()) throw bad("本领尚未达到要求。");
        });
        requirements.path("favorability").fields().forEachRemaining(entry -> {
            if (npc(game, entry.getKey()).path("favorability").asInt() < entry.getValue().asInt())
                throw bad("好感度尚未达到要求。");
        });
        Set<String> flags = new HashSet<>(); game.path("flags").forEach(flag -> flags.add(flag.asText()));
        requirements.path("flags").forEach(flag -> { if (!flags.contains(flag.asText())) throw bad("前置故事尚未完成。"); });
    }

    private ObjectNode requireCurrentAttempt(ObjectNode game, String attemptId) {
        JsonNode value = game.path("attempt");
        if (!value.isObject() || !attemptId.equals(value.path("id").asText())) throw bad("课卷已经变化，请重新载入。");
        return (ObjectNode) value;
    }
    private ObjectNode activeRun(ObjectNode game) {
        JsonNode run = adventure(game).path("run");
        if (!run.isObject()) throw bad("当前没有正在进行的活动。");
        return (ObjectNode) run;
    }
    private ObjectNode adventure(ObjectNode game) { return (ObjectNode) game.path("adventure"); }
    private ObjectNode game(String id) {
        WorldActionContext.Scope world = WorldActionContext.currentOrNull();
        return factory.hydrate(world == null ? games.findObject(id) : worldStates.find(world.learnerId(), world.worldId()));
    }
    private void persist(ObjectNode game) {
        WorldActionContext.Scope world = WorldActionContext.currentOrNull();
        if (world == null) games.save(game);
        else worldStates.save(world.learnerId(), world.worldId(), game);
    }
    private void ensureNoActiveRun(ObjectNode game) { if (adventure(game).path("run").isObject()) throw bad("请先结束眼前的行程。"); }
    private ObjectNode npc(ObjectNode game, String id) {
        for (JsonNode value : game.path("npcs")) if (id.equals(value.path("id").asText())) return (ObjectNode) value;
        throw bad("没有找到此人。");
    }
    private java.util.Optional<JsonNode> find(JsonNode values, String id) {
        for (JsonNode value : values) if (id.equals(value.path("id").asText())) return java.util.Optional.of(value);
        return java.util.Optional.empty();
    }
    private void journal(ObjectNode game, String title, String text) {
        ObjectNode entry = mapper.createObjectNode(); entry.put("id", UUID.randomUUID().toString());
        entry.put("day", game.path("records").size()); entry.put("title", title); entry.put("text", text); entry.put("kind", "milestone");
        game.withArray("journal").add(entry);
    }
    private static void addUnique(ArrayNode values, String value) {
        for (JsonNode item : values) if (value.equals(item.asText())) return;
        values.add(value);
    }
    private static ApiException bad(String message) { return new ApiException(HttpStatus.CONFLICT, message.trim()); }
}

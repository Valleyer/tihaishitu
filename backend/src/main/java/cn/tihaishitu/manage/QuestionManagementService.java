package cn.tihaishitu.manage;

import com.fasterxml.jackson.databind.JsonNode;
import cn.tihaishitu.catalog.QuestionContractValidator;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class QuestionManagementService {
    private static final Set<String> SOURCE_TYPES = Set.of("real_exam", "mock", "custom");
    private static final Set<String> QUESTION_TYPES = Set.of(
            "single_choice", "multiple_choice", "true_false", "solution");
    private static final Set<String> PRESENTATIONS = Set.of(
            "single_choice", "multiple_choice", "true_false", "self_assessment");
    private static final Set<String> GRADING_MODES = Set.of("auto", "self_assessment");
    private static final Set<String> RELATION_ROLES = Set.of("core", "auxiliary");

    private final QuestionManagementStore store;
    private final KnowledgeManagementStore knowledgeStore;

    public QuestionManagementService(QuestionManagementStore store, KnowledgeManagementStore knowledgeStore) {
        this.store = store;
        this.knowledgeStore = knowledgeStore;
    }

    public QuestionManagementStore.QuestionView create(
            QuestionManagementStore.QuestionInput input, Authentication auth) {
        validate(input);
        return store.create(input, actorId(auth));
    }

    public QuestionManagementStore.QuestionView update(
            String id, QuestionManagementStore.QuestionInput input, long expectedRevision, Authentication auth) {
        validate(input);
        var current = require(id);
        String actor = actorId(auth);
        return store.update(id, input, expectedRevision, actor);
    }

    public int bulkDelete(List<String> ids, Authentication auth) {
        if (ids == null || ids.isEmpty()) bad("请至少选择一道题。");
        if (ids.size() > 100) bad("一次最多删除 100 道题。");
        return store.bulkDelete(new java.util.LinkedHashSet<>(ids), actorId(auth));
    }

    public QuestionManagementStore.QuestionView submit(String id, long revision, Authentication auth) {
        var current = require(id); String actor = actorId(auth);
        if (!actor.equals(current.createdBy())) denied("只能提交自己创建的题目。");
        validateStored(current);
        if (!Set.of("draft", "rejected").contains(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前题目状态不能提交审核。");
        }
        return store.transition(id, revision, current.status(), "pending_review", actor,
                "QUESTION_SUBMITTED", "");
    }

    public QuestionManagementStore.QuestionView review(
            String id, long revision, boolean approve, String comment, Authentication auth) {
        var current = require(id); String actor = actorId(auth);
        if (actor.equals(current.createdBy())) denied("不能审核自己创建的题目。");
        if (!"pending_review".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待审核题目可以审核。");
        }
        if (approve) validateStored(current);
        String to = approve ? "published" : "rejected";
        return store.transition(id, revision, "pending_review", to, actor,
                approve ? "QUESTION_REVIEW_APPROVED" : "QUESTION_REVIEW_REJECTED", value(comment));
    }

    public QuestionManagementStore.QuestionView archive(
            String id, long revision, Authentication auth) {
        var current = require(id);
        if ("archived".equals(current.status())) return current;
        return store.transition(id, revision, current.status(), "archived", actorId(auth),
                "QUESTION_ARCHIVED", "");
    }

    private QuestionManagementStore.QuestionView require(String id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在。"));
    }

    private void validate(QuestionManagementStore.QuestionInput input) {
        if (input.subject() == null || input.subject().isBlank() || input.content() == null || input.content().isBlank()) {
            bad("科目和题干不能为空。");
        }
        rejectBlank(input.questionType());
        if (!SOURCE_TYPES.contains(input.sourceType()) || !QUESTION_TYPES.contains(input.questionType())
                || !PRESENTATIONS.contains(input.presentationType()) || !GRADING_MODES.contains(input.gradingMode())) {
            bad("来源、原始题型、展示类型或判题模式不合法。");
        }
        if (input.difficulty() < 1 || input.difficulty() > 5) bad("难度必须在 1–5 之间。");
        if ("real_exam".equals(input.sourceType()) && (input.examYear() == null || input.questionNumber() == null
                || input.questionNumber().isBlank())) bad("真题必须填写年份和题号。");
        if ("auto".equals(input.gradingMode()) && "self_assessment".equals(input.presentationType())) {
            bad("自动判题不能使用自评展示。");
        }
        if ("self_assessment".equals(input.gradingMode())
                && !"self_assessment".equals(input.presentationType())) bad("自评题必须使用自评展示。");
        if (input.standardAnswer() == null || input.standardAnswer().isNull()) bad("标准答案不能为空。");
        List<QuestionManagementStore.OptionInput> options = input.options() == null ? List.of() : input.options();
        List<QuestionManagementStore.RelationInput> relations = input.knowledgePoints() == null
                ? List.of() : input.knowledgePoints();
        if (relations.isEmpty() || relations.size() > 3) bad("题目必须关联 1–3 个知识点。");
        if ("auto".equals(input.gradingMode()) && !"true_false".equals(input.presentationType())
                && options.size() < 2) bad("自动选择题至少需要两个结构化选项。");
        Set<String> keys = new HashSet<>();
        for (var option : options) {
            if (option.key() == null || option.key().isBlank() || option.text() == null || option.text().isBlank()
                    || !keys.add(option.key())) bad("选项键和值不能为空，且选项键不能重复。");
        }
        QuestionContractValidator.validate(input.questionType(), input.presentationType(), input.gradingMode(),
                input.standardAnswer(), options.stream().map(option -> new QuestionContractValidator.Option(
                        option.key(), option.text(), option.correct())).toList()).ifPresent(QuestionManagementService::bad);
        Set<String> points = new HashSet<>();
        boolean hasCore = false;
        for (var relation : relations) {
            if (!RELATION_ROLES.contains(relation.role()) || !points.add(relation.knowledgePointId())) {
                bad("知识点关系角色不合法，或同一知识点被重复绑定。");
            }
            hasCore |= "core".equals(relation.role());
            var knowledge = knowledgeStore.find(relation.knowledgePointId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "题目引用了不存在的知识点。"));
            if (!"active".equals(knowledge.status())) bad("题目不能绑定已停用或已合并的知识点。");
        }
        if (!hasCore) bad("题目至少需要一个核心知识点。");
    }

    private void validateStored(QuestionManagementStore.QuestionView question) {
        QuestionContractValidator.validate(question.questionType(), question.presentationType(), question.gradingMode(),
                question.standardAnswer(), question.options().stream().map(option ->
                        new QuestionContractValidator.Option(option.key(), option.text(), option.correct())).toList())
                .ifPresent(QuestionManagementService::bad);
    }

    private static void rejectBlank(String type) {
        if ("blank".equals(type)) {
            bad(QuestionContractValidator.BLANK_ERROR);
        }
    }

    private String actorId(Authentication auth) { return knowledgeStore.userId(auth.getName()); }
    private static String value(String value) { return value == null ? "" : value; }
    private static void denied(String message) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

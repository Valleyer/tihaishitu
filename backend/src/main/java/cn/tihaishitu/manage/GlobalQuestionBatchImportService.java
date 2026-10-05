package cn.tihaishitu.manage;

import cn.tihaishitu.catalog.QuestionContractValidator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class GlobalQuestionBatchImportService {
    public static final String SCHEMA_VERSION = "global-question-batch/v2";

    private static final Set<String> FORBIDDEN_BOOK_FIELDS = Set.of(
            "bank", "book", "targetBookId", "weight", "enabled", "chapter");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "publish", "batch", "questions");
    private static final Set<String> BATCH_FIELDS = Set.of(
            "subject", "sourceType", "sourceName", "examYear");
    private static final Set<String> QUESTION_FIELDS = Set.of(
            "id", "questionNumber", "questionType", "presentationType", "gradingMode",
            "content", "standardAnswer", "analysis", "difficulty", "options", "knowledgePoints");
    private static final Set<String> OPTION_FIELDS = Set.of(
            "key", "text", "correct", "sortOrder");
    private static final Set<String> KNOWLEDGE_FIELDS = Set.of(
            "code", "role", "sortOrder");
    private static final Set<String> SOURCE_TYPES = Set.of("real_exam", "mock", "custom");
    private static final Set<String> QUESTION_TYPES = Set.of(
            "single_choice", "multiple_choice", "true_false", "solution");
    private static final Set<String> RELATION_ROLES = Set.of("core", "auxiliary");

    public record BatchInput(String subject, String sourceType, String sourceName, Integer examYear) {}
    public record OptionInput(String key, String text, Boolean correct, Integer sortOrder) {}
    public record KnowledgeInput(String code, String role, Integer sortOrder) {}
    public record QuestionInput(
            String id, String questionNumber, String questionType, String presentationType,
            String gradingMode, String content, JsonNode standardAnswer, String analysis,
            Integer difficulty, List<OptionInput> options, List<KnowledgeInput> knowledgePoints) {}
    public record ImportRequest(
            String schemaVersion, Boolean publish, BatchInput batch, List<QuestionInput> questions) {}
    public record ImportResult(
            String schemaVersion, String importId, boolean published, String subject,
            String sourceType, String sourceName, Integer examYear, int questionCount,
            int optionCount, int relationCount, int createdQuestions, int updatedQuestions) {}

    private record ValidBatch(String subject, String sourceType, String sourceName, Integer examYear) {}
    private record ResolvedKnowledge(String id, String code, String role, int sortOrder) {}
    private record ValidQuestion(QuestionInput input, List<OptionInput> options,
                                 List<ResolvedKnowledge> knowledgePoints) {}
    private record ValidImport(boolean publish, ValidBatch batch, List<ValidQuestion> questions) {}
    private record KnowledgeRef(String id, String subject) {}
    private record ExistingQuestionIdentity(
            String subject, String sourceType, Integer examYear, String questionNumber) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GlobalQuestionBatchImportService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public ImportResult importBatch(JsonNode document, String actorId) {
        ValidImport valid = validate(parse(document));
        String importId = UUID.randomUUID().toString();
        int created = 0;
        int updated = 0;
        int optionCount = 0;
        int relationCount = 0;

        for (ValidQuestion question : valid.questions()) {
            boolean existed = count("SELECT COUNT(*) FROM question_resource WHERE id = ?",
                    question.input().id()) > 0;
            upsertQuestion(valid.batch(), question.input(), valid.publish(), actorId);

            jdbc.update("DELETE FROM question_resource_option WHERE question_id = ?", question.input().id());
            for (OptionInput option : question.options()) {
                jdbc.update("""
                        INSERT INTO question_resource_option(
                            id, question_id, option_key, option_text, correct_option, sort_order
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), question.input().id(), option.key(),
                        option.text(), option.correct(), option.sortOrder());
                optionCount++;
            }

            jdbc.update("DELETE FROM question_resource_knowledge WHERE question_id = ?", question.input().id());
            for (ResolvedKnowledge point : question.knowledgePoints()) {
                jdbc.update("""
                        INSERT INTO question_resource_knowledge(
                            question_id, knowledge_point_id, relation_role, sort_order, created_by
                        ) VALUES (?, ?, ?, ?, ?)
                        """, question.input().id(), point.id(), point.role(), point.sortOrder(), actorId);
                relationCount++;
            }
            if (existed) updated++; else created++;
        }

        audit(actorId, importId, valid, optionCount, relationCount, created, updated);
        ValidBatch batch = valid.batch();
        return new ImportResult(SCHEMA_VERSION, importId, valid.publish(), batch.subject(),
                batch.sourceType(), batch.sourceName(), batch.examYear(), valid.questions().size(),
                optionCount, relationCount, created, updated);
    }

    private ImportRequest parse(JsonNode document) {
        if (document == null || !document.isObject()) bad("导入内容必须是 JSON 对象。");
        for (String field : FORBIDDEN_BOOK_FIELDS) {
            if (document.has(field)) {
                bad("V2 题目批次不得包含 Book 字段：" + field + "。");
            }
        }
        if ("global-question-bank/v1".equals(document.path("schemaVersion").asText())) {
            bad("这是旧版文集导入格式，请使用 global-question-batch/v2。");
        }
        validateSchemaFields(document);
        try {
            return mapper.treeToValue(document, ImportRequest.class);
        } catch (JsonProcessingException error) {
            bad("题目批次 JSON 字段类型不正确。");
            throw new IllegalStateException(error);
        }
    }

    private ValidImport validate(ImportRequest request) {
        if (request == null || !SCHEMA_VERSION.equals(request.schemaVersion())) {
            bad("schemaVersion 必须为 " + SCHEMA_VERSION + "。");
        }
        BatchInput inputBatch = request.batch();
        if (inputBatch == null || blank(inputBatch.subject()) || blank(inputBatch.sourceName())
                || !SOURCE_TYPES.contains(inputBatch.sourceType())) {
            bad("batch 必须提供合法的 subject、sourceType 和 sourceName。");
        }
        if ("real_exam".equals(inputBatch.sourceType()) && inputBatch.examYear() == null) {
            bad("real_exam 批次必须提供 examYear。");
        }
        ValidBatch batch = new ValidBatch(inputBatch.subject().trim(), inputBatch.sourceType(),
                inputBatch.sourceName().trim(), inputBatch.examYear());
        List<QuestionInput> questions = request.questions() == null ? List.of() : request.questions();
        if (questions.isEmpty()) bad("题目批次至少需要一道题。");
        if (questions.size() > 10_000) bad("单次导入不能超过 10000 道题。");

        Set<String> ids = new HashSet<>();
        Set<String> realExamNumbers = new HashSet<>();
        List<ValidQuestion> validated = new ArrayList<>();
        for (int index = 0; index < questions.size(); index++) {
            QuestionInput question = questions.get(index);
            String at = "第 " + (index + 1) + " 道题";
            if (question == null || !uuid(question.id()) || !ids.add(question.id())) {
                bad(at + "的 id 非法或重复。");
            }
            if (blank(question.content()) || blank(question.analysis()) || question.standardAnswer() == null
                    || question.standardAnswer().isNull()) {
                bad(at + "缺少题干、标准答案或解析。");
            }
            validateQuestionType(question, at);
            if (question.difficulty() == null || question.difficulty() < 1 || question.difficulty() > 5) {
                bad(at + "的 difficulty 必须为 1–5。");
            }
            ensureExistingQuestionIdentityCompatible(batch, question);
            if ("real_exam".equals(batch.sourceType())) {
                if (blank(question.questionNumber())) bad(at + "是真题，必须提供 questionNumber。");
                String number = question.questionNumber().trim();
                if (!realExamNumbers.add(number)) bad("同一真题批次的 questionNumber 不得重复：" + number + "。");
                ensureNaturalIdentity(batch, question.id(), number);
            }
            if (question.options() == null) bad(at + "必须提供 options 字段。");
            List<OptionInput> options = normalizeAndValidateOptions(question, at);
            QuestionContractValidator.validate(question.questionType(), question.presentationType(),
                    question.gradingMode(), question.standardAnswer(), options.stream().map(option ->
                            new QuestionContractValidator.Option(option.key(), option.text(),
                                    Boolean.TRUE.equals(option.correct()))).toList())
                    .ifPresent(message -> bad(at + "：" + message));
            if (question.knowledgePoints() == null) bad(at + "必须提供 knowledgePoints 字段。");
            List<ResolvedKnowledge> points = resolveKnowledgePoints(
                    question.knowledgePoints(), batch.subject(), at);
            validated.add(new ValidQuestion(question, options, points));
        }
        return new ValidImport(Boolean.TRUE.equals(request.publish()), batch, validated);
    }

    private void validateSchemaFields(JsonNode document) {
        rejectUnknownFields(document, TOP_LEVEL_FIELDS, "题目批次");
        JsonNode batch = document.get("batch");
        if (batch != null && batch.isObject()) {
            rejectUnknownFields(batch, BATCH_FIELDS, "batch");
        }
        JsonNode questions = document.get("questions");
        if (questions == null || !questions.isArray()) return;
        for (int questionIndex = 0; questionIndex < questions.size(); questionIndex++) {
            JsonNode question = questions.get(questionIndex);
            String at = "第 " + (questionIndex + 1) + " 道题";
            if (!question.isObject()) continue;
            rejectUnknownFields(question, QUESTION_FIELDS, at);
            JsonNode options = question.get("options");
            if (options != null && options.isArray()) {
                for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                    JsonNode option = options.get(optionIndex);
                    if (option.isObject()) {
                        rejectUnknownFields(option, OPTION_FIELDS,
                                at + "的第 " + (optionIndex + 1) + " 个选项");
                    }
                }
            }
            JsonNode knowledgePoints = question.get("knowledgePoints");
            if (knowledgePoints != null && knowledgePoints.isArray()) {
                for (int relationIndex = 0; relationIndex < knowledgePoints.size(); relationIndex++) {
                    JsonNode relation = knowledgePoints.get(relationIndex);
                    if (relation.isObject()) {
                        rejectUnknownFields(relation, KNOWLEDGE_FIELDS,
                                at + "的第 " + (relationIndex + 1) + " 个知识点关系");
                    }
                }
            }
        }
    }

    private void rejectUnknownFields(JsonNode object, Set<String> allowed, String at) {
        List<String> unknown = new ArrayList<>();
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) unknown.add(field);
        });
        if (!unknown.isEmpty()) {
            bad(at + "包含未知字段：" + String.join("、", unknown) + "。");
        }
    }

    private void ensureExistingQuestionIdentityCompatible(ValidBatch batch, QuestionInput question) {
        List<ExistingQuestionIdentity> existing = jdbc.query("""
                SELECT subject_name, source_type, exam_year, question_number
                  FROM question_resource
                 WHERE id = ?
                """, (row, index) -> new ExistingQuestionIdentity(
                row.getString("subject_name"), row.getString("source_type"),
                row.getObject("exam_year", Integer.class), row.getString("question_number")),
                question.id());
        if (existing.isEmpty()) return;

        ExistingQuestionIdentity identity = existing.get(0);
        boolean eitherRealExam = "real_exam".equals(identity.sourceType())
                || "real_exam".equals(batch.sourceType());
        boolean compatible;
        if (eitherRealExam) {
            compatible = "real_exam".equals(identity.sourceType())
                    && "real_exam".equals(batch.sourceType())
                    && Objects.equals(identity.subject(), batch.subject())
                    && Objects.equals(identity.examYear(), batch.examYear())
                    && Objects.equals(normalizeIdentity(identity.questionNumber()),
                    normalizeIdentity(question.questionNumber()));
        } else {
            compatible = Objects.equals(identity.subject(), batch.subject())
                    && Objects.equals(identity.sourceType(), batch.sourceType());
        }
        if (!compatible) {
            bad("Question UUID 已属于另一道题，不能通过批量导入改变其稳定身份。");
        }
    }

    private void validateQuestionType(QuestionInput question, String at) {
        if ("blank".equals(question.questionType())) {
            bad(QuestionContractValidator.BLANK_ERROR);
        }
        if (!QUESTION_TYPES.contains(question.questionType())) {
            bad(at + "的 questionType 不合法。");
        }
        String expectedPresentation;
        String expectedGrading;
        switch (question.questionType()) {
            case "single_choice" -> { expectedPresentation = "single_choice"; expectedGrading = "auto"; }
            case "multiple_choice" -> { expectedPresentation = "multiple_choice"; expectedGrading = "auto"; }
            case "true_false" -> { expectedPresentation = "true_false"; expectedGrading = "auto"; }
            case "solution" -> { expectedPresentation = "self_assessment"; expectedGrading = "self_assessment"; }
            default -> throw new IllegalStateException("未覆盖的题型：" + question.questionType());
        }
        if (!expectedPresentation.equals(question.presentationType())
                || !expectedGrading.equals(question.gradingMode())) {
            bad(at + "的 questionType、presentationType 与 gradingMode 不匹配。");
        }
    }

    private List<OptionInput> normalizeAndValidateOptions(QuestionInput question, String at) {
        List<OptionInput> options = question.options();
        if ("self_assessment".equals(question.gradingMode())) {
            if (!options.isEmpty()) bad(at + "是自评题，不应提供客观题选项。");
            if (!question.standardAnswer().isTextual() || blank(question.standardAnswer().asText())) {
                bad(at + "的自评参考答案必须是 Markdown 字符串。");
            }
            return List.of();
        }
        if (options.size() < 2 || options.size() > 6) bad(at + "必须提供 2–6 个选项。");
        Set<String> keys = new HashSet<>();
        Set<String> correct = new HashSet<>();
        List<OptionInput> normalized = new ArrayList<>();
        for (int index = 0; index < options.size(); index++) {
            OptionInput option = options.get(index);
            if (option == null || blank(option.key()) || blank(option.text()) || option.correct() == null) {
                bad(at + "存在字段不完整的选项。");
            }
            String key = option.key().trim();
            if (!keys.add(key)) bad(at + "存在重复选项 key：" + key + "。");
            if (option.correct()) correct.add(key);
            normalized.add(new OptionInput(key, option.text().trim(), option.correct(),
                    option.sortOrder() == null ? index : option.sortOrder()));
        }
        if ("single_choice".equals(question.questionType()) && correct.size() != 1) {
            bad(at + "的单选题必须且只能有一个正确选项。");
        }
        if ("multiple_choice".equals(question.questionType()) && correct.size() < 2) {
            bad(at + "的多选题至少需要两个正确选项。");
        }
        if ("true_false".equals(question.questionType())
                && (options.size() != 2 || correct.size() != 1)) {
            bad(at + "的判断题必须有两个选项和一个正确项。");
        }
        if (!answerKeys(question.standardAnswer()).equals(correct)) {
            bad(at + "的 standardAnswer 与 options.correct 不一致。");
        }
        return normalized;
    }

    private List<ResolvedKnowledge> resolveKnowledgePoints(
            List<KnowledgeInput> relations, String subject, String at) {
        if (relations.isEmpty() || relations.size() > 3) bad(at + "必须关联 1–3 个知识点。");
        Set<String> codes = new HashSet<>();
        boolean hasCore = false;
        List<ResolvedKnowledge> resolved = new ArrayList<>();
        for (int index = 0; index < relations.size(); index++) {
            KnowledgeInput relation = relations.get(index);
            if (relation == null || blank(relation.code()) || !RELATION_ROLES.contains(relation.role())) {
                bad(at + "存在非法的知识点关系。");
            }
            String code = relation.code().trim();
            if (!codes.add(code)) bad(at + "存在重复知识点 code：" + code + "。");
            KnowledgeRef point = activeKnowledge(code);
            if (point == null) bad(at + "引用了不存在或已停用的知识点 code：" + code + "。");
            if (!subject.equals(point.subject())) {
                bad(at + "引用的知识点 " + code + " 不属于 batch.subject：" + subject + "。");
            }
            hasCore |= "core".equals(relation.role());
            resolved.add(new ResolvedKnowledge(point.id(), code, relation.role(),
                    relation.sortOrder() == null ? index : relation.sortOrder()));
        }
        if (!hasCore) bad(at + "至少需要一个 core 知识点。");
        return resolved;
    }

    private void ensureNaturalIdentity(ValidBatch batch, String questionId, String questionNumber) {
        List<String> existingIds = jdbc.query("""
                SELECT id FROM question_resource
                 WHERE subject_name = ? AND source_type = 'real_exam'
                   AND exam_year = ? AND question_number = ?
                """, (row, index) -> row.getString("id"),
                batch.subject(), batch.examYear(), questionNumber);
        if (existingIds.stream().anyMatch(id -> !id.equals(questionId))) {
            bad(batch.examYear() + " " + batch.subject() + "第" + questionNumber
                    + "题已存在，但 UUID 不一致；请复用原 Question UUID，不要创建重复真题。");
        }
    }

    private KnowledgeRef activeKnowledge(String code) {
        List<KnowledgeRef> points = jdbc.query("""
                SELECT id, subject_name FROM global_knowledge_point
                 WHERE code = ? AND status = 'active'
                """, (row, index) -> new KnowledgeRef(row.getString("id"), row.getString("subject_name")), code);
        return points.isEmpty() ? null : points.get(0);
    }

    private void upsertQuestion(ValidBatch batch, QuestionInput question, boolean publish, String actorId) {
        String status = publish ? "published" : "pending_review";
        String answer = json(question.standardAnswer());
        int changed = jdbc.update("""
                UPDATE question_resource
                   SET subject_name = ?, source_type = ?, source_name = ?, exam_year = ?,
                       question_number = ?, question_type = ?, presentation_type = ?, grading_mode = ?,
                       content_markdown = ?, standard_answer_json = ?, analysis_markdown = ?, difficulty = ?,
                       status = ?, updated_by = ?, reviewed_by = NULL, reviewed_at = NULL,
                       review_comment = NULL, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, batch.subject(), batch.sourceType(), batch.sourceName(), batch.examYear(),
                nullable(question.questionNumber()), question.questionType(), question.presentationType(),
                question.gradingMode(), question.content().trim(), answer, question.analysis().trim(),
                question.difficulty(), status, actorId, question.id());
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO question_resource(
                        id, subject_name, source_type, source_name, exam_year, question_number,
                        question_type, presentation_type, grading_mode, content_markdown,
                        standard_answer_json, analysis_markdown, difficulty, status,
                        created_by, updated_by, revision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """, question.id(), batch.subject(), batch.sourceType(), batch.sourceName(), batch.examYear(),
                    nullable(question.questionNumber()), question.questionType(), question.presentationType(),
                    question.gradingMode(), question.content().trim(), answer, question.analysis().trim(),
                    question.difficulty(), status, actorId, actorId);
        }
    }

    private void audit(String actorId, String importId, ValidImport valid, int optionCount,
                       int relationCount, int created, int updated) {
        ValidBatch batch = valid.batch();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", SCHEMA_VERSION);
        metadata.put("publish", valid.publish());
        metadata.put("subject", batch.subject());
        metadata.put("sourceType", batch.sourceType());
        metadata.put("sourceName", batch.sourceName());
        metadata.put("examYear", batch.examYear());
        metadata.put("questionCount", valid.questions().size());
        metadata.put("createdQuestions", created);
        metadata.put("updatedQuestions", updated);
        metadata.put("optionCount", optionCount);
        metadata.put("relationCount", relationCount);
        jdbc.update("""
                INSERT INTO content_audit_log(
                    id, actor_learner_id, action_name, entity_type, entity_id, metadata_json)
                VALUES (?, ?, 'QUESTION_BATCH_IMPORTED', 'question_batch', ?, ?)
                """, UUID.randomUUID().toString(), actorId, importId, json(metadata));
    }

    private Set<String> answerKeys(JsonNode answer) {
        Set<String> result = new HashSet<>();
        if (answer.isTextual()) result.add(answer.asText().trim());
        else if (answer.isArray()) answer.forEach(node -> {
            if (node.isTextual()) result.add(node.asText().trim());
        });
        else if (answer.isBoolean()) result.add(answer.asBoolean() ? "true" : "false");
        return result;
    }

    private long count(String sql, Object... params) {
        Long value = jdbc.queryForObject(sql, Long.class, params);
        return value == null ? 0 : value;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("导入数据无法序列化。", error);
        }
    }

    private static boolean uuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullable(String value) {
        return blank(value) ? null : value.trim();
    }

    private static String normalizeIdentity(String value) {
        return blank(value) ? null : value.strip();
    }

    private static void bad(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

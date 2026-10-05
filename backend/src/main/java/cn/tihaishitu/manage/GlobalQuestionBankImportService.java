package cn.tihaishitu.manage;

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
import java.util.Set;
import java.util.UUID;

@Service
@Deprecated(forRemoval = false)
public class GlobalQuestionBankImportService {
    private static final String SCHEMA_VERSION = "global-question-bank/v1";
    private static final Set<String> SOURCE_TYPES = Set.of("real_exam", "mock", "custom");
    private static final Set<String> QUESTION_TYPES = Set.of(
            "single_choice", "multiple_choice", "true_false", "solution");
    private static final Set<String> PRESENTATIONS = Set.of(
            "single_choice", "multiple_choice", "true_false", "self_assessment");
    private static final Set<String> GRADING_MODES = Set.of("auto", "self_assessment");
    private static final Set<String> RELATION_ROLES = Set.of("core", "auxiliary");

    public record BankInput(String id, String name, String description, Boolean enabled, Integer weight) {}
    public record OptionInput(String key, String text, Boolean correct, Integer sortOrder) {}
    public record KnowledgeInput(String code, String role, Integer sortOrder) {}
    public record QuestionInput(
            String id, String subject, String sourceType, String sourceName, Integer examYear,
            String questionNumber, String questionType, String presentationType, String gradingMode,
            String content, JsonNode standardAnswer, String analysis, Integer difficulty,
            List<OptionInput> options, List<KnowledgeInput> knowledgePoints) {}
    public record ImportRequest(String schemaVersion, Boolean publish, BankInput bank, List<QuestionInput> questions) {}
    public record ImportResult(
            String schemaVersion, String bankId, String bankName, boolean published,
            int questionCount, int optionCount, int relationCount, int createdQuestions,
            int updatedQuestions) {}

    private record ResolvedKnowledge(String id, String code, String role, int sortOrder) {}
    private record ValidQuestion(QuestionInput input, List<OptionInput> options, List<ResolvedKnowledge> points) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public GlobalQuestionBankImportService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public ImportResult importBank(ImportRequest request, String actorId) {
        ValidImport valid = validate(request);
        BankInput bank = valid.bank();
        if (count("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?", bank.id()) > 0) {
            bad("该 Book 已使用 KnowledgePoint 学习路径，禁止旧版覆盖导入；请使用 global-question-batch/v2。");
        }
        boolean publish = Boolean.TRUE.equals(request.publish());
        upsertBank(bank);

        int created = 0;
        int updated = 0;
        int optionCount = 0;
        int relationCount = 0;
        jdbc.update("DELETE FROM question_bank_item WHERE bank_id = ?", bank.id());
        for (int index = 0; index < valid.questions().size(); index++) {
            ValidQuestion question = valid.questions().get(index);
            boolean existed = count("SELECT COUNT(*) FROM question_resource WHERE id = ?", question.input().id()) > 0;
            upsertQuestion(question.input(), publish, actorId);
            jdbc.update("DELETE FROM question_resource_option WHERE question_id = ?", question.input().id());
            for (OptionInput option : question.options()) {
                jdbc.update("""
                        INSERT INTO question_resource_option(
                            id, question_id, option_key, option_text, correct_option, sort_order
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), question.input().id(), option.key().trim(),
                        option.text().trim(), Boolean.TRUE.equals(option.correct()), option.sortOrder());
                optionCount++;
            }
            jdbc.update("DELETE FROM question_resource_knowledge WHERE question_id = ?", question.input().id());
            for (ResolvedKnowledge point : question.points()) {
                jdbc.update("""
                        INSERT INTO question_resource_knowledge(
                            question_id, knowledge_point_id, relation_role, sort_order, created_by
                        ) VALUES (?, ?, ?, ?, ?)
                        """, question.input().id(), point.id(), point.role(), point.sortOrder(), actorId);
                relationCount++;
            }
            jdbc.update("INSERT INTO question_bank_item(bank_id, question_id, sort_order) VALUES (?, ?, ?)",
                    bank.id(), question.input().id(), index);
            if (existed) updated++; else created++;
        }
        audit(actorId, bank.id(), publish, valid.questions().size(), created, updated);
        return new ImportResult(SCHEMA_VERSION, bank.id(), bank.name().trim(), publish,
                valid.questions().size(), optionCount, relationCount, created, updated);
    }

    private record ValidImport(BankInput bank, List<ValidQuestion> questions) {}

    private ValidImport validate(ImportRequest request) {
        if (request == null || !SCHEMA_VERSION.equals(request.schemaVersion())) {
            bad("schemaVersion 必须为 " + SCHEMA_VERSION + "。");
        }
        BankInput bank = request.bank();
        if (bank == null || !uuid(bank.id()) || blank(bank.name())) bad("文集必须提供合法 UUID 和名称。");
        int weight = bank.weight() == null ? 5 : bank.weight();
        if (weight < 0 || weight > 100) bad("文集 weight 必须在 0–100 之间。");
        List<QuestionInput> questions = request.questions() == null ? List.of() : request.questions();
        if (questions.isEmpty()) bad("导入文集至少需要一道题。 ");
        if (questions.size() > 10_000) bad("单次导入不能超过 10000 道题。");
        Set<String> questionIds = new HashSet<>();
        List<ValidQuestion> validated = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionInput q = questions.get(i);
            String at = "第 " + (i + 1) + " 道题";
            if (q == null || !uuid(q.id()) || !questionIds.add(q.id())) bad(at + "的 id 非法或重复。");
            if (blank(q.subject()) || blank(q.content()) || q.standardAnswer() == null || q.standardAnswer().isNull()) {
                bad(at + "缺少科目、题干或标准答案。");
            }
            if ("blank".equals(q.questionType())) {
                bad("知境不支持填空题；原填空题必须在生成阶段转换为单选题或多选题。");
            }
            if (!SOURCE_TYPES.contains(q.sourceType()) || !QUESTION_TYPES.contains(q.questionType())
                    || !PRESENTATIONS.contains(q.presentationType()) || !GRADING_MODES.contains(q.gradingMode())) {
                bad(at + "的来源、原始题型、展示类型或判题模式不合法。");
            }
            if (q.difficulty() == null || q.difficulty() < 1 || q.difficulty() > 5) {
                bad(at + "的 difficulty 必须为 1–5。");
            }
            if ("real_exam".equals(q.sourceType())
                    && (q.examYear() == null || blank(q.questionNumber()))) {
                bad(at + "是真题，必须填写 examYear 和 questionNumber。");
            }
            boolean selfAssessment = "self_assessment".equals(q.gradingMode());
            if (!validCombination(q.questionType(), q.presentationType(), q.gradingMode())) {
                bad(at + "的 questionType、presentationType 与 gradingMode 不匹配。");
            }
            if (selfAssessment && !q.standardAnswer().isTextual()) {
                bad(at + "的自评参考答案必须是 Markdown 字符串。");
            }
            List<OptionInput> options = q.options() == null ? List.of() : q.options();
            validateOptions(q, options, at);
            List<KnowledgeInput> relations = q.knowledgePoints() == null ? List.of() : q.knowledgePoints();
            if (relations.isEmpty() || relations.size() > 3) bad(at + "必须关联 1–3 个知识点。");
            Set<String> codes = new HashSet<>();
            boolean hasCore = false;
            List<ResolvedKnowledge> resolved = new ArrayList<>();
            for (int relationIndex = 0; relationIndex < relations.size(); relationIndex++) {
                KnowledgeInput relation = relations.get(relationIndex);
                if (relation == null || blank(relation.code()) || !codes.add(relation.code())
                        || !RELATION_ROLES.contains(relation.role())) {
                    bad(at + "存在重复或非法的知识点关系。");
                }
                String id = knowledgeId(relation.code());
                if (id == null) bad(at + "引用了不存在或已停用的知识点 code：" + relation.code());
                hasCore |= "core".equals(relation.role());
                resolved.add(new ResolvedKnowledge(id, relation.code(), relation.role(),
                        relation.sortOrder() == null ? relationIndex : relation.sortOrder()));
            }
            if (!hasCore) bad(at + "至少需要一个 core 知识点。");
            validated.add(new ValidQuestion(q, normalizedOptions(options), resolved));
        }
        return new ValidImport(new BankInput(bank.id(), bank.name(), value(bank.description()),
                bank.enabled() == null || bank.enabled(), weight), validated);
    }

    private void validateOptions(QuestionInput q, List<OptionInput> options, String at) {
        if ("self_assessment".equals(q.gradingMode())) {
            if (!options.isEmpty()) bad(at + "是自评题，不应提供客观题选项。");
            return;
        }
        if (options.size() < 2 || options.size() > 6) bad(at + "必须提供 2–6 个选项。");
        Set<String> keys = new HashSet<>();
        Set<String> correct = new HashSet<>();
        for (OptionInput option : options) {
            if (option == null || blank(option.key()) || blank(option.text()) || !keys.add(option.key().trim())) {
                bad(at + "存在空白或重复选项。");
            }
            if (Boolean.TRUE.equals(option.correct())) correct.add(option.key().trim());
        }
        if ("single_choice".equals(q.presentationType()) && correct.size() != 1) bad(at + "必须且只能有一个正确选项。");
        if ("multiple_choice".equals(q.presentationType()) && correct.size() < 2) bad(at + "至少需要两个正确选项。");
        if ("true_false".equals(q.presentationType()) && (options.size() != 2 || correct.size() != 1)) {
            bad(at + "的判断题必须有两个选项和一个正确项。");
        }
        Set<String> answerKeys = answerKeys(q.standardAnswer());
        if (!answerKeys.equals(correct)) bad(at + "的 standardAnswer 与 options.correct 不一致。");
    }

    private static boolean validCombination(String type, String presentation, String grading) {
        return switch (type) {
            case "single_choice" -> "single_choice".equals(presentation) && "auto".equals(grading);
            case "multiple_choice" -> "multiple_choice".equals(presentation) && "auto".equals(grading);
            case "true_false" -> "true_false".equals(presentation) && "auto".equals(grading);
            case "solution" -> "self_assessment".equals(presentation) && "self_assessment".equals(grading);
            default -> false;
        };
    }

    private List<OptionInput> normalizedOptions(List<OptionInput> options) {
        List<OptionInput> result = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            OptionInput option = options.get(i);
            result.add(new OptionInput(option.key(), option.text(), option.correct(),
                    option.sortOrder() == null ? i : option.sortOrder()));
        }
        return result;
    }

    private Set<String> answerKeys(JsonNode answer) {
        Set<String> result = new HashSet<>();
        if (answer.isTextual()) result.add(answer.asText());
        else if (answer.isArray()) answer.forEach(node -> { if (node.isTextual()) result.add(node.asText()); });
        else if (answer.isBoolean()) result.add(answer.asBoolean() ? "true" : "false");
        return result;
    }

    private String knowledgeId(String code) {
        List<String> ids = jdbc.query("""
                SELECT id FROM global_knowledge_point WHERE code = ? AND status = 'active'
                """, (row, index) -> row.getString("id"), code.trim());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void upsertBank(BankInput bank) {
        int changed = jdbc.update("""
                UPDATE question_bank SET name = ?, description = ?, enabled = ?, weight_value = ?,
                    revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, bank.name().trim(), bank.description(), bank.enabled(), bank.weight(), bank.id());
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO question_bank(id, name, description, enabled, weight_value, revision)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """, bank.id(), bank.name().trim(), bank.description(), bank.enabled(), bank.weight());
        }
    }

    private void upsertQuestion(QuestionInput q, boolean publish, String actorId) {
        String status = publish ? "published" : "pending_review";
        String answer = json(q.standardAnswer());
        int changed = jdbc.update("""
                UPDATE question_resource SET subject_name = ?, source_type = ?, source_name = ?, exam_year = ?,
                    question_number = ?, question_type = ?, presentation_type = ?, grading_mode = ?,
                    content_markdown = ?, standard_answer_json = ?, analysis_markdown = ?, difficulty = ?,
                    status = ?, updated_by = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, q.subject().trim(), q.sourceType(), nullable(q.sourceName()), q.examYear(),
                nullable(q.questionNumber()), q.questionType(), q.presentationType(), q.gradingMode(),
                q.content().trim(), answer, value(q.analysis()), q.difficulty(), status, actorId, q.id());
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO question_resource(
                        id, subject_name, source_type, source_name, exam_year, question_number,
                        question_type, presentation_type, grading_mode, content_markdown,
                        standard_answer_json, analysis_markdown, difficulty, status,
                        created_by, updated_by, revision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """, q.id(), q.subject().trim(), q.sourceType(), nullable(q.sourceName()), q.examYear(),
                    nullable(q.questionNumber()), q.questionType(), q.presentationType(), q.gradingMode(),
                    q.content().trim(), answer, value(q.analysis()), q.difficulty(), status, null, actorId);
        }
    }

    private void audit(String actorId, String bankId, boolean publish, int count, int created, int updated) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", SCHEMA_VERSION);
        metadata.put("published", publish);
        metadata.put("questionCount", count);
        metadata.put("createdQuestions", created);
        metadata.put("updatedQuestions", updated);
        jdbc.update("""
                INSERT INTO content_audit_log(id, actor_learner_id, action_name, entity_type, entity_id, metadata_json)
                VALUES (?, ?, 'QUESTION_BANK_IMPORTED', 'question_bank', ?, ?)
                """, UUID.randomUUID().toString(), actorId, bankId, json(metadata));
    }

    private long count(String sql, Object... params) {
        Long value = jdbc.queryForObject(sql, Long.class, params);
        return value == null ? 0 : value;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("导入数据无法序列化。", error); }
    }

    private static boolean uuid(String value) {
        try { UUID.fromString(value); return true; }
        catch (RuntimeException error) { return false; }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static String nullable(String value) { return blank(value) ? null : value.trim(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

package cn.tihaishitu.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class LegacyCatalogMigrator {
    private static final Logger log = LoggerFactory.getLogger(LegacyCatalogMigrator.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public LegacyCatalogMigrator(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(200)
    @Transactional
    public void migrateAfterSeed() {
        int points = migrateKnowledge();
        int questions = migrateQuestions();
        if (points + questions > 0) {
            log.info("已将旧文集投影到全服资源层：{} 个知识点，{} 道题。", points, questions);
        }
    }

    private int migrateKnowledge() {
        List<LegacyPoint> points = jdbc.query("""
                SELECT bank_id, id, name, subject_name, category_name, description, explanation, sort_order
                  FROM knowledge_point ORDER BY bank_id, sort_order
                """, (r, row) -> new LegacyPoint(r.getString("bank_id"), r.getString("id"), r.getString("name"),
                r.getString("subject_name"), r.getString("category_name"), r.getString("description"),
                r.getString("explanation"), r.getInt("sort_order")));
        int inserted = 0;
        for (LegacyPoint point : points) {
            if (mapping("legacy_knowledge_map", point.bankId(), point.id()) != null) continue;
            String globalId = canonicalMatch(point);
            if (globalId == null) {
                globalId = stableUuid("legacy-knowledge:" + point.bankId() + ":" + point.id());
                String code = "LEGACY-" + globalId;
                if (!exists("global_knowledge_point", globalId)) jdbc.update("""
                        INSERT INTO global_knowledge_point(
                            id, code, name, subject_name, section_name, chapter_name, default_role,
                            status, description, explanation, introduced_version, sort_order, revision
                        ) VALUES (?, ?, ?, ?, ?, ?, 'core', 'active', ?, ?, 'legacy-v1', ?, 1)
                        """, globalId, code, point.name(), point.subject(), point.category(), point.category(),
                        value(point.description()), value(point.explanation()), point.sortOrder());
                inserted++;
            }
            jdbc.update("INSERT INTO legacy_knowledge_map(bank_id, legacy_id, global_id) VALUES (?, ?, ?)",
                    point.bankId(), point.id(), globalId);
        }
        return inserted;
    }

    private int migrateQuestions() {
        List<LegacyQuestion> questions = jdbc.query("""
                SELECT q.bank_id, q.id, q.subject_name, q.category_name, q.chapter_name, q.question_type,
                       q.question_text, q.answer_json, q.explanation, q.difficulty, q.sort_order, b.name bank_name
                  FROM question_item q JOIN question_bank b ON b.id = q.bank_id
                 ORDER BY q.bank_id, q.sort_order
                """, (r, row) -> new LegacyQuestion(r.getString("bank_id"), r.getString("id"),
                r.getString("subject_name"), r.getString("category_name"), r.getString("chapter_name"),
                r.getString("question_type"), r.getString("question_text"), r.getString("answer_json"),
                r.getString("explanation"), r.getInt("difficulty"), r.getInt("sort_order"),
                r.getString("bank_name")));
        int inserted = 0;
        for (LegacyQuestion question : questions) {
            String globalId = mapping("legacy_question_map", question.bankId(), question.id());
            if (globalId == null) {
                globalId = stableUuid("legacy-question:" + question.bankId() + ":" + question.id());
                if (!exists("question_resource", globalId)) jdbc.update("""
                        INSERT INTO question_resource(
                            id, subject_name, source_type, source_name, question_type, presentation_type,
                            grading_mode, content_markdown, standard_answer_json, analysis_markdown,
                            difficulty, status, derivation_type, revision
                        ) VALUES (?, ?, 'custom', ?, ?, ?, 'auto', ?, ?, ?, ?, 'published', 'legacy_migration', 1)
                        """, globalId, question.subject(), question.bankName(), question.type(), question.type(),
                        question.content(), question.answerJson(), value(question.explanation()), question.difficulty());
                if (!hasChildren("question_resource_option", globalId)) migrateOptions(question, globalId);
                if (!hasChildren("question_resource_knowledge", globalId)) migrateRelations(question, globalId);
                jdbc.update("INSERT INTO legacy_question_map(bank_id, legacy_id, global_id) VALUES (?, ?, ?)",
                        question.bankId(), question.id(), globalId);
                inserted++;
            }
            Integer linked = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ? AND question_id = ?
                    """, Integer.class, question.bankId(), globalId);
            if (linked == null || linked == 0) {
                jdbc.update("INSERT INTO question_bank_item(bank_id, question_id, sort_order) VALUES (?, ?, ?)",
                        question.bankId(), globalId, question.sortOrder());
            }
        }
        return inserted;
    }

    private void migrateOptions(LegacyQuestion question, String globalId) {
        JsonNode answer = read(question.answerJson());
        List<OptionRow> options = jdbc.query("""
                SELECT option_key, option_text, sort_order FROM question_option
                 WHERE bank_id = ? AND question_id = ? ORDER BY sort_order
                """, (r, row) -> new OptionRow(r.getString("option_key"), r.getString("option_text"),
                r.getInt("sort_order")), question.bankId(), question.id());
        for (OptionRow option : options) {
            jdbc.update("""
                    INSERT INTO question_resource_option(id, question_id, option_key, option_text, correct_option, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), globalId, option.key(), option.text(),
                    contains(answer, option.key()), option.sortOrder());
        }
    }

    private void migrateRelations(LegacyQuestion question, String globalId) {
        List<String> legacyIds = jdbc.query("""
                SELECT knowledge_point_id FROM question_knowledge_point
                 WHERE bank_id = ? AND question_id = ? ORDER BY sort_order
                """, (r, row) -> r.getString("knowledge_point_id"), question.bankId(), question.id());
        int order = 0;
        for (String legacyId : legacyIds) {
            String pointId = mapping("legacy_knowledge_map", question.bankId(), legacyId);
            if (pointId != null) {
                jdbc.update("""
                        INSERT INTO question_resource_knowledge(question_id, knowledge_point_id, relation_role, sort_order)
                        VALUES (?, ?, 'core', ?)
                        """, globalId, pointId, order++);
            }
        }
    }

    private String canonicalMatch(LegacyPoint point) {
        List<String> values = jdbc.query("""
                SELECT id FROM global_knowledge_point
                 WHERE subject_name = ? AND name = ? AND status = 'active' ORDER BY code
                """, (r, row) -> r.getString("id"), point.subject(), point.name());
        return values.size() == 1 ? values.get(0) : null;
    }

    private String mapping(String table, String bankId, String legacyId) {
        if (!SetOfTables.ALLOWED.contains(table)) throw new IllegalArgumentException("Invalid mapping table");
        List<String> values = jdbc.query("SELECT global_id FROM " + table + " WHERE bank_id = ? AND legacy_id = ?",
                (r, row) -> r.getString("global_id"), bankId, legacyId);
        return values.isEmpty() ? null : values.get(0);
    }

    private boolean exists(String table, String id) {
        if (!java.util.Set.of("global_knowledge_point", "question_resource").contains(table))
            throw new IllegalArgumentException("Invalid resource table");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    private boolean hasChildren(String table, String questionId) {
        if (!java.util.Set.of("question_resource_option", "question_resource_knowledge").contains(table))
            throw new IllegalArgumentException("Invalid child table");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE question_id = ?",
                Integer.class, questionId);
        return count != null && count > 0;
    }

    private JsonNode read(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException error) { throw new IllegalStateException("旧题答案 JSON 损坏。", error); }
    }

    private static boolean contains(JsonNode answer, String key) {
        if (answer.isArray()) {
            for (JsonNode item : answer) if (key.equals(item.asText())) return true;
            return false;
        }
        return answer.isBoolean() ? Boolean.toString(answer.asBoolean()).equals(key) : key.equals(answer.asText());
    }
    private static String stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String value(String value) { return value == null ? "" : value; }
    private record LegacyPoint(String bankId, String id, String name, String subject, String category,
                               String description, String explanation, int sortOrder) {}
    private record LegacyQuestion(String bankId, String id, String subject, String category, String chapter,
                                  String type, String content, String answerJson, String explanation,
                                  int difficulty, int sortOrder, String bankName) {}
    private record OptionRow(String key, String text, int sortOrder) {}
    private static final class SetOfTables {
        private static final java.util.Set<String> ALLOWED = java.util.Set.of(
                "legacy_knowledge_map", "legacy_question_map");
    }
}

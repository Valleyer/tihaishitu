package cn.tihaishitu.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CatalogStore {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CatalogStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public int countBanks() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank", Integer.class);
        return count == null ? 0 : count;
    }

    public long catalogRevision() {
        Long revision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision), 0) FROM question_bank", Long.class);
        return revision == null ? 0 : revision;
    }

    public List<QuestionBankDto> findAll() {
        return jdbc.query(
                "SELECT id, name, description, enabled, weight_value FROM question_bank ORDER BY created_at, id",
                (result, row) -> loadBank(result)
        );
    }

    public List<QuestionBankManifest> findManifests() {
        return jdbc.query(
                """
                SELECT b.id, b.name, b.description, b.enabled, b.weight_value, b.revision,
                       CASE WHEN EXISTS (SELECT 1 FROM question_bank_item x WHERE x.bank_id = b.id)
                            THEN (SELECT COUNT(*) FROM question_bank_item bi
                                   JOIN question_resource qr ON qr.id = bi.question_id
                                  WHERE bi.bank_id = b.id AND qr.status = 'published')
                            ELSE (SELECT COUNT(*) FROM question_item q WHERE q.bank_id = b.id) END question_count,
                       CASE WHEN EXISTS (SELECT 1 FROM question_bank_knowledge bk WHERE bk.bank_id = b.id)
                            THEN (SELECT COUNT(*) FROM question_bank_knowledge bk
                                   JOIN global_knowledge_point k ON k.id = bk.knowledge_point_id
                                  WHERE bk.bank_id = b.id AND k.status = 'active')
                            WHEN EXISTS (SELECT 1 FROM question_bank_item x WHERE x.bank_id = b.id)
                            THEN (SELECT COUNT(DISTINCT qk.knowledge_point_id)
                           FROM question_bank_item bi
                           JOIN question_resource qr ON qr.id = bi.question_id
                           JOIN question_resource_knowledge qk ON qk.question_id = bi.question_id
                          WHERE bi.bank_id = b.id AND qr.status = 'published')
                            ELSE (SELECT COUNT(*) FROM knowledge_point k WHERE k.bank_id = b.id) END point_count
                  FROM question_bank b
                 ORDER BY b.created_at, b.id
                """,
                (result, row) -> new QuestionBankManifest(
                        result.getString("id"), result.getString("name"), result.getString("description"),
                        result.getBoolean("enabled"), result.getInt("weight_value"), result.getLong("revision"),
                        result.getInt("question_count"), result.getInt("point_count")
                )
        );
    }

    public Optional<QuestionBankDto> findById(String id) {
        List<QuestionBankDto> banks = jdbc.query(
                "SELECT id, name, description, enabled, weight_value FROM question_bank WHERE id = ?",
                (result, row) -> loadBank(result), id);
        return banks.stream().findFirst();
    }

    public long revisionOf(String id) {
        List<Long> revisions = jdbc.query(
                "SELECT revision FROM question_bank WHERE id = ?",
                (result, row) -> result.getLong("revision"), id);
        return revisions.isEmpty() ? -1 : revisions.get(0);
    }

    private QuestionBankDto loadBank(ResultSet result) throws SQLException {
        String bankId = result.getString("id");
        return new QuestionBankDto(
                bankId,
                result.getString("name"),
                result.getString("description"),
                loadPlayableKnowledgePoints(bankId),
                loadQuestions(bankId),
                result.getBoolean("enabled"),
                result.getInt("weight_value")
        );
    }

    public List<KnowledgePointDto> loadBookKnowledgePoints(String bankId) {
        return jdbc.query(
                """
                SELECT k.id, k.name, k.subject_name, k.section_name, k.chapter_name,
                       k.description, k.explanation, bk.sort_order
                  FROM question_bank_knowledge bk
                  JOIN global_knowledge_point k ON k.id = bk.knowledge_point_id
                 WHERE bk.bank_id = ? AND k.status = 'active'
                 ORDER BY bk.sort_order, k.id
                """,
                (result, row) -> knowledgePoint(result),
                bankId
        );
    }

    public List<KnowledgePointDto> loadPlayableKnowledgePoints(String bankId) {
        if (!hasProjectedItems(bankId)) return loadLegacyKnowledgePoints(bankId);
        String membershipJoin = hasBookKnowledgeMemberships(bankId)
                ? "JOIN question_bank_knowledge bk ON bk.bank_id = bi.bank_id AND bk.knowledge_point_id = qk.knowledge_point_id"
                : "";
        return jdbc.query(
                """
                SELECT DISTINCT k.id, k.name, k.subject_name, k.section_name, k.chapter_name,
                       k.description, k.explanation, k.sort_order
                   FROM question_bank_item bi
                   JOIN question_resource q ON q.id = bi.question_id
                   JOIN question_resource_knowledge qk ON qk.question_id = bi.question_id
                   %s
                   JOIN global_knowledge_point k ON k.id = qk.knowledge_point_id
                  WHERE bi.bank_id = ? AND q.status = 'published' AND k.status = 'active'
                 ORDER BY k.sort_order, k.id
                """.formatted(membershipJoin),
                (result, row) -> knowledgePoint(result),
                bankId
        );
    }

    private static KnowledgePointDto knowledgePoint(ResultSet result) throws SQLException {
        return new KnowledgePointDto(
                result.getString("id"), result.getString("name"),
                result.getString("subject_name"), result.getString("section_name"),
                result.getString("description"), result.getString("explanation"),
                null, List.of(), List.of(result.getString("chapter_name"))
        );
    }

    private List<QuestionDto> loadQuestions(String bankId) {
        if (!hasProjectedItems(bankId)) return loadLegacyQuestions(bankId);
        Map<String, Map<String, String>> options = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT o.question_id, o.option_key, o.option_text
                   FROM question_bank_item bi
                   JOIN question_resource q ON q.id = bi.question_id
                   JOIN question_resource_option o ON o.question_id = bi.question_id
                  WHERE bi.bank_id = ? AND q.status = 'published'
                 ORDER BY o.question_id, o.sort_order
                """,
                (RowCallbackHandler) result -> options
                        .computeIfAbsent(result.getString("question_id"), ignored -> new LinkedHashMap<>())
                        .put(result.getString("option_key"), result.getString("option_text")),
                bankId
        );
        Map<String, List<String>> pointIds = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT qk.question_id, qk.knowledge_point_id
                   FROM question_bank_item bi
                   JOIN question_resource q ON q.id = bi.question_id
                   JOIN question_resource_knowledge qk ON qk.question_id = bi.question_id
                  WHERE bi.bank_id = ? AND q.status = 'published'
                 ORDER BY qk.question_id, qk.sort_order
                """,
                (RowCallbackHandler) result -> pointIds
                        .computeIfAbsent(result.getString("question_id"), ignored -> new ArrayList<>())
                        .add(result.getString("knowledge_point_id")),
                bankId
        );
        return jdbc.query(
                """
                SELECT q.id, q.subject_name, q.source_type, q.source_name, q.question_type,
                       q.presentation_type, q.grading_mode, q.content_markdown,
                       q.standard_answer_json, q.analysis_markdown, q.difficulty
                  FROM question_bank_item bi
                  JOIN question_resource q ON q.id = bi.question_id
                 WHERE bi.bank_id = ? AND q.status = 'published'
                 ORDER BY bi.sort_order, q.id
                """,
                (result, row) -> {
                    String id = result.getString("id");
                    return new QuestionDto(
                            id, result.getString("subject_name"), result.getString("source_type"),
                            value(result.getString("source_name"), "全服题库"),
                            result.getString("presentation_type"), result.getString("question_type"),
                            result.getString("presentation_type"), result.getString("grading_mode"),
                            result.getString("content_markdown"), options.getOrDefault(id, Map.of()),
                            readTree(result.getString("standard_answer_json")), result.getString("analysis_markdown"),
                            List.of(), List.of(), result.getInt("difficulty"), 3, List.of(),
                            pointIds.getOrDefault(id, List.of()), true
                    );
                },
                bankId
        );
    }

    private boolean hasProjectedItems(String bankId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ?",
                Integer.class, bankId);
        return count != null && count > 0;
    }

    private boolean hasBookKnowledgeMemberships(String bankId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?", Integer.class, bankId);
        return count != null && count > 0;
    }

    private List<KnowledgePointDto> loadLegacyKnowledgePoints(String bankId) {
        return jdbc.query("""
                SELECT id, name, subject_name, category_name, description, explanation,
                       parent_id, prerequisites_json, tags_json
                  FROM knowledge_point WHERE bank_id = ? ORDER BY sort_order, id
                """, (result, row) -> new KnowledgePointDto(
                result.getString("id"), result.getString("name"), result.getString("subject_name"),
                result.getString("category_name"), result.getString("description"),
                result.getString("explanation"), result.getString("parent_id"),
                readStringList(result.getString("prerequisites_json")),
                readStringList(result.getString("tags_json"))), bankId);
    }

    private List<QuestionDto> loadLegacyQuestions(String bankId) {
        Map<String, Map<String, String>> options = new LinkedHashMap<>();
        jdbc.query("""
                SELECT question_id, option_key, option_text FROM question_option
                 WHERE bank_id = ? ORDER BY question_id, sort_order
                """, (RowCallbackHandler) result -> options
                .computeIfAbsent(result.getString("question_id"), ignored -> new LinkedHashMap<>())
                .put(result.getString("option_key"), result.getString("option_text")), bankId);
        Map<String, List<String>> pointIds = new LinkedHashMap<>();
        jdbc.query("""
                SELECT question_id, knowledge_point_id FROM question_knowledge_point
                 WHERE bank_id = ? ORDER BY question_id, sort_order
                """, (RowCallbackHandler) result -> pointIds
                .computeIfAbsent(result.getString("question_id"), ignored -> new ArrayList<>())
                .add(result.getString("knowledge_point_id")), bankId);
        return jdbc.query("""
                SELECT id, subject_name, category_name, chapter_name, question_type,
                       question_text, answer_json, explanation, aliases_json, keywords_json,
                       difficulty, frequency_value, tags_json, enabled
                  FROM question_item WHERE bank_id = ? ORDER BY sort_order, id
                """, (result, row) -> {
            String id = result.getString("id");
            String type = result.getString("question_type");
            return new QuestionDto(id, result.getString("subject_name"), result.getString("category_name"),
                    result.getString("chapter_name"), type, type, type, "auto",
                    result.getString("question_text"), options.getOrDefault(id, Map.of()),
                    readTree(result.getString("answer_json")), result.getString("explanation"),
                    readStringList(result.getString("aliases_json")),
                    readStringList(result.getString("keywords_json")), result.getInt("difficulty"),
                    result.getInt("frequency_value"), readStringList(result.getString("tags_json")),
                    pointIds.getOrDefault(id, List.of()), result.getBoolean("enabled"));
        }, bankId);
    }

    @Transactional
    public void replaceAll(List<QuestionBankDto> banks) {
        jdbc.update("DELETE FROM question_bank");
        for (QuestionBankDto bank : banks) insert(bank, 1);
    }

    @Transactional
    public void upsert(QuestionBankDto bank) {
        long revision = revisionOf(bank.id());
        if (revision >= 0) jdbc.update("DELETE FROM question_bank WHERE id = ?", bank.id());
        insert(bank, revision < 0 ? 1 : revision + 1);
    }

    public boolean updateMetadata(String id, String name, String description, boolean enabled, int weight) {
        return jdbc.update(
                """
                UPDATE question_bank
                   SET name = ?, description = ?, enabled = ?, weight_value = ?,
                       revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """,
                name, description, enabled, weight, id
        ) > 0;
    }

    private void insert(QuestionBankDto bank, long revision) {
        jdbc.update(
                "INSERT INTO question_bank(id, name, description, enabled, weight_value, revision) VALUES (?, ?, ?, ?, ?, ?)",
                bank.id(), bank.name(), bank.description(), bank.enabled(), bank.weight(), revision
        );
        int pointOrder = 0;
        for (KnowledgePointDto point : bank.knowledgePoints()) {
            jdbc.update(
                    """
                    INSERT INTO knowledge_point(
                        bank_id, id, name, subject_name, category_name, description, explanation,
                        parent_id, prerequisites_json, tags_json, sort_order
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    bank.id(), point.id(), point.name(), point.subject(), point.category(),
                    point.description(), point.explanation(), point.parentId(), json(point.prerequisites()),
                    json(point.tags()), pointOrder++
            );
        }
        int questionOrder = 0;
        for (QuestionDto question : bank.questions()) {
            jdbc.update(
                    """
                    INSERT INTO question_item(
                        bank_id, id, subject_name, category_name, chapter_name, question_type,
                        question_text, answer_json, explanation, aliases_json, keywords_json,
                        tags_json, difficulty, frequency_value, enabled, sort_order
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    bank.id(), question.id(), question.subject(), question.category(), question.chapter(),
                    question.type(), question.question(), json(question.answer()), question.explanation(),
                    json(question.aliases()), json(question.keywords()), json(question.tags()),
                    question.difficulty(), question.frequency(), question.enabled(), questionOrder++
            );
            int optionOrder = 0;
            for (Map.Entry<String, String> option : question.options().entrySet()) {
                jdbc.update(
                        "INSERT INTO question_option(bank_id, question_id, option_key, option_text, sort_order) VALUES (?, ?, ?, ?, ?)",
                        bank.id(), question.id(), option.getKey(), option.getValue(), optionOrder++
                );
            }
            int pointLinkOrder = 0;
            for (String pointId : question.knowledgePointIds()) {
                jdbc.update(
                        "INSERT INTO question_knowledge_point(bank_id, question_id, knowledge_point_id, sort_order) VALUES (?, ?, ?, ?)",
                        bank.id(), question.id(), pointId, pointLinkOrder++
                );
            }
        }
    }

    private List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库中的字符串数组不是合法 JSON。", error);
        }
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库中的答案不是合法 JSON。", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("题库内容无法序列化。", error);
        }
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

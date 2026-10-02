package cn.tihaishitu.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class KnowledgeManagementStore {
    public record KnowledgeView(
            String id, String code, String name, String subject, String section, String chapter,
            String defaultRole, String status, String description, String explanation,
            String introducedVersion, String mergedIntoId, int sortOrder, long revision,
            List<String> aliases, int questionCount) {}

    public record KnowledgeUpdate(
            String name, String defaultRole, String status, String description, String explanation,
            String mergedIntoId, List<String> aliases, long expectedRevision) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeManagementStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PageResult<KnowledgeView> search(
            String query, String subject, String section, String chapter, String status, int page, int size) {
        SqlFilter filter = filter(query, subject, section, chapter, status);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM global_knowledge_point k " + filter.where(),
                Long.class, filter.params().toArray());
        List<Object> params = new ArrayList<>(filter.params());
        params.add(size);
        params.add(page * size);
        List<KnowledgeView> rows = jdbc.query(baseSelect() + filter.where()
                        + " ORDER BY k.sort_order, k.code LIMIT ? OFFSET ?",
                (result, row) -> map(result.getString("id"), result), params.toArray());
        return PageResult.of(rows, page, size, total == null ? 0 : total);
    }

    public Optional<KnowledgeView> find(String id) {
        List<KnowledgeView> rows = jdbc.query(baseSelect() + " WHERE k.id = ?",
                (result, row) -> map(id, result), id);
        return rows.stream().findFirst();
    }

    @Transactional
    public KnowledgeView update(String id, KnowledgeUpdate update, String actorId) {
        int changed = jdbc.update("""
                UPDATE global_knowledge_point
                   SET name = ?, default_role = ?, status = ?, description = ?, explanation = ?,
                       merged_into_id = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND revision = ?
                """, update.name(), update.defaultRole(), update.status(), update.description(), update.explanation(),
                emptyToNull(update.mergedIntoId()), id, update.expectedRevision());
        if (changed == 0) {
            if (find(id).isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "知识点不存在。");
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "知识点已被其他人修改，请重新加载。");
        }
        jdbc.update("DELETE FROM knowledge_alias WHERE knowledge_point_id = ?", id);
        for (String alias : update.aliases()) {
            if (alias == null || alias.isBlank()) continue;
            jdbc.update("INSERT INTO knowledge_alias(id, knowledge_point_id, alias) VALUES (?, ?, ?)",
                    UUID.randomUUID().toString(), id, alias.trim());
        }
        audit(actorId, "KNOWLEDGE_UPDATED", "knowledge_point", id,
                java.util.Map.of("expectedRevision", update.expectedRevision()));
        return find(id).orElseThrow();
    }

    public String userId(String username) {
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", String.class, username);
    }

    public void audit(String actorId, String action, String type, String entityId, Object metadata) {
        try {
            jdbc.update("""
                    INSERT INTO content_audit_log(id, actor_user_id, action_name, entity_type, entity_id, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), actorId, action, type, entityId,
                    mapper.writeValueAsString(metadata));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("审计信息无法序列化。", error);
        }
    }

    private KnowledgeView map(String id, java.sql.ResultSet result) throws java.sql.SQLException {
        return new KnowledgeView(id, result.getString("code"), result.getString("name"),
                result.getString("subject_name"), result.getString("section_name"),
                result.getString("chapter_name"), result.getString("default_role"),
                result.getString("status"), result.getString("description"), result.getString("explanation"),
                result.getString("introduced_version"), result.getString("merged_into_id"),
                result.getInt("sort_order"), result.getLong("revision"), aliases(id), result.getInt("question_count"));
    }

    private List<String> aliases(String id) {
        return jdbc.query("SELECT alias FROM knowledge_alias WHERE knowledge_point_id = ? ORDER BY alias",
                (result, row) -> result.getString("alias"), id);
    }

    private static String baseSelect() {
        return """
                SELECT k.*,
                       (SELECT COUNT(*) FROM question_resource_knowledge qk
                         WHERE qk.knowledge_point_id = k.id) question_count
                  FROM global_knowledge_point k
                """;
    }

    private static SqlFilter filter(String query, String subject, String section, String chapter, String status) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String value = "%" + query.trim().toLowerCase() + "%";
            clauses.add("(LOWER(k.code) LIKE ? OR LOWER(k.name) LIKE ? OR EXISTS "
                    + "(SELECT 1 FROM knowledge_alias a WHERE a.knowledge_point_id = k.id AND LOWER(a.alias) LIKE ?))");
            params.add(value); params.add(value); params.add(value);
        }
        add(clauses, params, "k.subject_name", subject);
        add(clauses, params, "k.section_name", section);
        add(clauses, params, "k.chapter_name", chapter);
        add(clauses, params, "k.status", status);
        return new SqlFilter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    private static void add(List<String> clauses, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) { clauses.add(column + " = ?"); params.add(value.trim()); }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SqlFilter(String where, List<Object> params) {}
}

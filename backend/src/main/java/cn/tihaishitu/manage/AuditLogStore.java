package cn.tihaishitu.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class AuditLogStore {
    public record AuditLogView(String id, String actorUserId, String actorUsername, String actorDisplayName,
                               String action, String entityType, String entityId,
                               JsonNode metadata, LocalDateTime createdAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditLogStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PageResult<AuditLogView> search(String action, String entityType, String actor, int page, int size) {
        List<String> clauses = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        add(clauses, values, "a.action_name", action);
        add(clauses, values, "a.entity_type", entityType);
        if (actor != null && !actor.isBlank()) {
            clauses.add("(a.actor_user_id = ? OR LOWER(u.username) LIKE ? OR LOWER(u.display_name) LIKE ?)");
            values.add(actor.trim());
            values.add("%" + actor.trim().toLowerCase() + "%");
            values.add("%" + actor.trim().toLowerCase() + "%");
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM content_audit_log a LEFT JOIN app_user u ON u.id = a.actor_user_id
                """ + where, Long.class, values.toArray());
        List<Object> queryValues = new ArrayList<>(values);
        queryValues.add(size);
        queryValues.add(page * size);
        List<AuditLogView> rows = jdbc.query("""
                SELECT a.*, u.username actor_username, u.display_name actor_display_name
                  FROM content_audit_log a
                  LEFT JOIN app_user u ON u.id = a.actor_user_id
                """ + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
                (result, index) -> new AuditLogView(
                        result.getString("id"), result.getString("actor_user_id"),
                        result.getString("actor_username"), result.getString("actor_display_name"),
                        result.getString("action_name"), result.getString("entity_type"),
                        result.getString("entity_id"), json(result.getString("metadata_json")),
                        result.getTimestamp("created_at").toLocalDateTime()), queryValues.toArray());
        return PageResult.of(rows, page, size, total == null ? 0 : total);
    }

    private JsonNode json(String value) {
        try { return mapper.readTree(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("审计日志 JSON 已损坏。", error); }
    }

    private static void add(List<String> clauses, List<Object> values, String column, String value) {
        if (value != null && !value.isBlank()) {
            clauses.add(column + " = ?");
            values.add(value.trim());
        }
    }
}

package cn.tihaishitu.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class QuestionSourceManagementStore {
    public record SourceView(String id, String sourceType, String canonicalName, String displayName,
                             String status, long revision, long questionCount, Instant updatedAt) {}
    public record SourceInput(String sourceType, String canonicalName, String displayName, String status) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public QuestionSourceManagementStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PageResult<SourceView> search(String query, String sourceType, String status, int page, int size) {
        List<String> clauses = new ArrayList<>(); List<Object> values = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            clauses.add("(LOWER(s.canonical_name) LIKE ? OR LOWER(s.display_name) LIKE ?)");
            String value = "%" + query.trim().toLowerCase() + "%"; values.add(value); values.add(value);
        }
        add(clauses, values, "s.source_type", sourceType); add(clauses, values, "s.status", status);
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM question_source s" + where,
                Long.class, values.toArray());
        List<Object> params = new ArrayList<>(values); params.add(size); params.add(page * size);
        List<SourceView> rows = jdbc.query(select() + where
                        + " GROUP BY s.id,s.source_type,s.canonical_name,s.display_name,s.status,s.revision,s.updated_at"
                        + " ORDER BY s.updated_at DESC,s.id LIMIT ? OFFSET ?",
                (result, index) -> map(result), params.toArray());
        return PageResult.of(rows, page, size, total == null ? 0 : total);
    }

    public Optional<SourceView> find(String id) {
        return jdbc.query(select() + " WHERE s.id=? GROUP BY s.id,s.source_type,s.canonical_name,"
                        + "s.display_name,s.status,s.revision,s.updated_at",
                (result, index) -> map(result), id).stream().findFirst();
    }

    public Optional<SourceView> findByIdentity(String type, String canonicalName) {
        return jdbc.query(select() + " WHERE s.source_type=? AND s.canonical_name=? GROUP BY s.id,s.source_type,"
                        + "s.canonical_name,s.display_name,s.status,s.revision,s.updated_at",
                (result, index) -> map(result), type, canonicalName).stream().findFirst();
    }

    @Transactional
    public SourceView create(SourceInput input, String actorId) {
        if (findByIdentity(input.sourceType(), input.canonicalName()).isPresent()) duplicate();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_source(id,source_type,canonical_name,display_name,status,revision) VALUES (?,?,?,?,?,1)",
                id, input.sourceType(), input.canonicalName(), input.displayName(), input.status());
        audit(actorId, "SOURCE_CREATED", id, Map.of("revision", 1, "sourceType", input.sourceType(),
                "canonicalName", input.canonicalName(), "displayName", input.displayName(), "status", input.status()));
        return find(id).orElseThrow();
    }

    @Transactional
    public SourceView update(String id, SourceInput input, long expectedRevision, String actorId) {
        Optional<SourceView> duplicate = findByIdentity(input.sourceType(), input.canonicalName());
        if (duplicate.isPresent() && !duplicate.get().id().equals(id)) duplicate();
        SourceView before = find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "来源不存在。"));
        int changed = jdbc.update("UPDATE question_source SET source_type=?,canonical_name=?,display_name=?,status=?,"
                        + "revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND revision=?",
                input.sourceType(), input.canonicalName(), input.displayName(), input.status(), id, expectedRevision);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "来源已被其他人修改，请重新加载。");
        jdbc.update("UPDATE question_resource SET source_type=?,source_name=?,updated_at=CURRENT_TIMESTAMP WHERE source_id=?",
                input.sourceType(), input.canonicalName(), id);
        audit(actorId, "SOURCE_UPDATED", id, Map.of("expectedRevision", expectedRevision,
                "from", Map.of("sourceType", before.sourceType(), "canonicalName", before.canonicalName(),
                        "displayName", before.displayName(), "status", before.status()),
                "to", Map.of("sourceType", input.sourceType(), "canonicalName", input.canonicalName(),
                        "displayName", input.displayName(), "status", input.status())));
        return find(id).orElseThrow();
    }

    private void audit(String actorId, String action, String id, Object metadata) {
        try {
            jdbc.update("INSERT INTO content_audit_log(id,actor_learner_id,action_name,entity_type,entity_id,metadata_json) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), actorId, action, "question_source", id, mapper.writeValueAsString(metadata));
        } catch (JsonProcessingException error) { throw new IllegalStateException("来源审计信息无法序列化。", error); }
    }
    private SourceView map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new SourceView(r.getString("id"), r.getString("source_type"), r.getString("canonical_name"),
                r.getString("display_name"), r.getString("status"), r.getLong("revision"),
                r.getLong("question_count"), r.getTimestamp("updated_at").toInstant());
    }
    private static String select() { return "SELECT s.*,COUNT(q.id) question_count FROM question_source s LEFT JOIN question_resource q ON q.source_id=s.id"; }
    private static void add(List<String> clauses, List<Object> values, String column, String value) {
        if (value != null && !value.isBlank()) { clauses.add(column + "=?"); values.add(value.trim()); }
    }
    private static void duplicate() { throw new ResponseStatusException(HttpStatus.CONFLICT, "相同类型和正式名称的来源已经存在。"); }
}

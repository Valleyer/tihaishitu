package cn.tihaishitu.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class KnowledgeManagementStore {
    public record BookMembership(String bookId, String bookName, String chapterId, String chapterName) {}
    public record KnowledgeView(
            String id, String code, String name, String subject, String section, String chapter,
            String defaultRole, String status, String description, String explanation,
            String introducedVersion, String mergedIntoId, int sortOrder, long revision,
            List<String> aliases, int questionCount, List<BookMembership> books) {}

    public record KnowledgeFacets(List<String> subjects) {}

    public record KnowledgeUpdate(
            String name, String defaultRole, String status, String description, String explanation,
            String mergedIntoId, List<String> aliases, long expectedRevision) {}

    public record KnowledgeMergeResult(
            String historyId, KnowledgeView source, KnowledgeView target,
            int migratedRelations, int collapsedRelations, int affectedQuestions) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public KnowledgeManagementStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PageResult<KnowledgeView> search(
            String query, String subject, String section, String chapter, String status,
            String bookId, String chapterId, String membership, int page, int size) {
        SqlFilter filter = filter(query, subject, section, chapter, status, bookId, chapterId, membership);
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

    public KnowledgeFacets facets() {
        return new KnowledgeFacets(jdbc.query("""
                SELECT DISTINCT subject_name FROM global_knowledge_point
                 WHERE subject_name IS NOT NULL AND subject_name <> '' ORDER BY subject_name
                """, (result, row) -> result.getString("subject_name")));
    }

    public Optional<KnowledgeView> find(String id) {
        List<KnowledgeView> rows = jdbc.query(baseSelect() + " WHERE k.id = ?",
                (result, row) -> map(id, result), id);
        return rows.stream().findFirst();
    }

    @Transactional
    public KnowledgeView update(String id, KnowledgeUpdate update, String actorId) {
        KnowledgeView existing = find(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "知识点不存在。"));
        String requestedMerge = emptyToNull(update.mergedIntoId());
        if (!java.util.Objects.equals(existing.mergedIntoId(), requestedMerge)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "知识点合并必须使用专用合并操作。");
        }
        if (existing.mergedIntoId() != null && "active".equals(update.status())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "已合并知识点不能重新启用。");
        }
        if ("active".equals(existing.status()) && "deprecated".equals(update.status())
                && existing.questionCount() > 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "已有题目绑定的知识点必须通过合并操作停用。");
        }
        Set<String> previousAliases = new LinkedHashSet<>(existing.aliases());
        Set<String> nextAliases = normalizeAliases(update.aliases());
        int changed = jdbc.update("""
                UPDATE global_knowledge_point
                   SET name = ?, default_role = ?, status = ?, description = ?, explanation = ?,
                       merged_into_id = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND revision = ?
                """, update.name(), update.defaultRole(), update.status(), update.description(), update.explanation(),
                requestedMerge, id, update.expectedRevision());
        if (changed == 0) {
            if (find(id).isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "知识点不存在。");
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "知识点已被其他人修改，请重新加载。");
        }
        jdbc.update("DELETE FROM knowledge_alias WHERE knowledge_point_id = ?", id);
        for (String alias : nextAliases) {
            jdbc.update("INSERT INTO knowledge_alias(id, knowledge_point_id, alias) VALUES (?, ?, ?)",
                    UUID.randomUUID().toString(), id, alias);
        }
        audit(actorId, "KNOWLEDGE_UPDATED", "knowledge_point", id,
                java.util.Map.of("expectedRevision", update.expectedRevision()));
        if (!previousAliases.equals(nextAliases)) {
            audit(actorId, "KNOWLEDGE_ALIASES_UPDATED", "knowledge_point", id,
                    java.util.Map.of("before", previousAliases, "after", nextAliases));
        }
        return find(id).orElseThrow();
    }

    @Transactional
    public KnowledgeMergeResult merge(String sourceId, String targetId, long expectedRevision,
                                      String reason, String actorId) {
        if (sourceId.equals(targetId)) bad("知识点不能合并到自身。");
        KnowledgeView source = find(sourceId).orElseThrow(() -> missing("源知识点不存在。"));
        KnowledgeView target = find(targetId).orElseThrow(() -> missing("目标知识点不存在。"));
        if (!"active".equals(source.status()) || source.mergedIntoId() != null) bad("源知识点已经停用或合并。");
        if (!"active".equals(target.status()) || target.mergedIntoId() != null) bad("目标知识点必须是有效知识点。");
        if (!source.subject().equals(target.subject())) bad("只能合并同一学科的知识点。");
        if (reason == null || reason.isBlank()) bad("请填写合并原因。");

        List<String> membershipBankIds = jdbc.query("""
                SELECT bank_id
                  FROM question_bank_knowledge
                 WHERE knowledge_point_id = ?
                 ORDER BY bank_id
                """, (row, index) -> row.getString("bank_id"), sourceId);
        Set<String> bankIds = new LinkedHashSet<>();
        int migratedBookMemberships = 0;
        int collapsedBookMemberships = 0;
        for (String membershipBankId : membershipBankIds) {
            bankIds.add(membershipBankId);
            Integer targetMembership = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM question_bank_knowledge
                     WHERE bank_id = ? AND knowledge_point_id = ?
                    """, Integer.class, membershipBankId, targetId);
            if (targetMembership == null || targetMembership == 0) {
                jdbc.update("""
                        UPDATE question_bank_knowledge SET knowledge_point_id = ?
                         WHERE bank_id = ? AND knowledge_point_id = ?
                        """, targetId, membershipBankId, sourceId);
                migratedBookMemberships++;
            } else {
                jdbc.update("""
                        DELETE FROM question_bank_knowledge
                         WHERE bank_id = ? AND knowledge_point_id = ?
                        """, membershipBankId, sourceId);
                collapsedBookMemberships++;
            }
        }

        List<RelationRow> relations = jdbc.query("""
                SELECT question_id, relation_role, sort_order
                  FROM question_resource_knowledge
                 WHERE knowledge_point_id = ?
                 ORDER BY question_id
                """, (row, index) -> new RelationRow(row.getString("question_id"),
                row.getString("relation_role"), row.getInt("sort_order")), sourceId);
        Set<String> questionIds = new LinkedHashSet<>();
        int migrated = 0;
        int collapsed = 0;
        for (RelationRow relation : relations) {
            questionIds.add(relation.questionId());
            List<RelationRow> existing = jdbc.query("""
                    SELECT question_id, relation_role, sort_order
                      FROM question_resource_knowledge
                     WHERE question_id = ? AND knowledge_point_id = ?
                    """, (row, index) -> new RelationRow(row.getString("question_id"),
                    row.getString("relation_role"), row.getInt("sort_order")), relation.questionId(), targetId);
            if (existing.isEmpty()) {
                jdbc.update("""
                        UPDATE question_resource_knowledge SET knowledge_point_id = ?
                         WHERE question_id = ? AND knowledge_point_id = ?
                        """, targetId, relation.questionId(), sourceId);
                migrated++;
            } else {
                RelationRow targetRelation = existing.get(0);
                String role = "core".equals(relation.role()) || "core".equals(targetRelation.role())
                        ? "core" : "auxiliary";
                jdbc.update("""
                        UPDATE question_resource_knowledge SET relation_role = ?, sort_order = ?
                         WHERE question_id = ? AND knowledge_point_id = ?
                        """, role, Math.min(relation.sortOrder(), targetRelation.sortOrder()),
                        relation.questionId(), targetId);
                jdbc.update("DELETE FROM question_resource_knowledge WHERE question_id = ? AND knowledge_point_id = ?",
                        relation.questionId(), sourceId);
                collapsed++;
            }
        }

        for (String questionId : questionIds) {
            bankIds.addAll(jdbc.query("SELECT bank_id FROM question_bank_item WHERE question_id = ?",
                    (row, index) -> row.getString("bank_id"), questionId));
        }
        for (String bankId : bankIds) {
            jdbc.update("UPDATE question_bank SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    bankId);
        }

        int sourceChanged = jdbc.update("""
                UPDATE global_knowledge_point
                   SET status = 'deprecated', merged_into_id = ?, revision = revision + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND revision = ? AND status = 'active' AND merged_into_id IS NULL
                """, targetId, sourceId, expectedRevision);
        if (sourceChanged == 0) conflictOrMissing(sourceId);
        jdbc.update("""
                UPDATE global_knowledge_point SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, targetId);

        Set<String> searchableNames = new LinkedHashSet<>();
        searchableNames.add(source.code());
        searchableNames.add(source.name());
        searchableNames.addAll(source.aliases());
        for (String alias : searchableNames) addAliasIfAbsent(targetId, target, alias);

        String historyId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO knowledge_merge_history(
                    id, source_knowledge_id, target_knowledge_id, actor_learner_id,
                    migrated_relation_count, collapsed_relation_count, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, historyId, sourceId, targetId, actorId, migrated, collapsed, reason.trim());
        audit(actorId, "KNOWLEDGE_MERGED", "knowledge_point", sourceId, java.util.Map.of(
                "targetId", targetId,
                "migratedRelations", migrated,
                "collapsedRelations", collapsed,
                "affectedQuestions", questionIds.size(),
                "affectedBanks", bankIds.size(),
                "migratedBookMemberships", migratedBookMemberships,
                "collapsedBookMemberships", collapsedBookMemberships,
                "reason", reason.trim(),
                "historyId", historyId));
        return new KnowledgeMergeResult(historyId, find(sourceId).orElseThrow(), find(targetId).orElseThrow(),
                migrated, collapsed, questionIds.size());
    }

    public String userId(String username) {
        return jdbc.queryForObject("SELECT id FROM learner_account WHERE username = ?", String.class, username);
    }

    public void audit(String actorId, String action, String type, String entityId, Object metadata) {
        try {
            jdbc.update("""
                    INSERT INTO content_audit_log(id, actor_learner_id, action_name, entity_type, entity_id, metadata_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), actorId, action, type, entityId,
                    mapper.writeValueAsString(metadata));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("审计信息无法序列化。", error);
        }
    }

    public String deleteBlockedReason(String id) {
        int questions = count("question_resource_knowledge", "knowledge_point_id", id);
        if (questions > 0) return "仍有 " + questions + " 道题绑定";
        int memberships = count("question_bank_knowledge", "knowledge_point_id", id);
        if (memberships > 0) return "仍属于 " + memberships + " 部文集";
        if (count("legacy_knowledge_map", "global_id", id) > 0) return "仍被旧文集映射引用";
        int merges = countWhere("""
                SELECT COUNT(*) FROM knowledge_merge_history
                 WHERE source_knowledge_id=? OR target_knowledge_id=?
                """, id, id) + count("global_knowledge_point", "merged_into_id", id);
        if (merges > 0) return "存在知识点合并历史";
        int learning = count("learner_focus_knowledge", "knowledge_point_id", id)
                + count("study_attempt", "target_knowledge_point_id", id)
                + count("learner_knowledge_state", "knowledge_point_id", id)
                + count("learner_knowledge_evidence", "knowledge_point_id", id)
                + count("learner_diagnosis_session", "target_knowledge_point_id", id)
                + count("learner_diagnosis_dependency", "knowledge_point_id", id)
                + count("learner_practice_session", "target_knowledge_point_id", id)
                + count("learner_practice_scope", "knowledge_point_id", id);
        if (learning > 0) return "存在正式学习历史";
        return null;
    }

    public boolean lockForDelete(String id) {
        return !jdbc.query("SELECT id FROM global_knowledge_point WHERE id=? FOR UPDATE",
                (result, row) -> result.getString("id"), id).isEmpty();
    }

    public int deleteOrphan(String id) {
        return jdbc.update("DELETE FROM global_knowledge_point WHERE id=?", id);
    }

    private KnowledgeView map(String id, java.sql.ResultSet result) throws java.sql.SQLException {
        return new KnowledgeView(id, result.getString("code"), result.getString("name"),
                result.getString("subject_name"), result.getString("section_name"),
                result.getString("chapter_name"), result.getString("default_role"),
                result.getString("status"), result.getString("description"), result.getString("explanation"),
                result.getString("introduced_version"), result.getString("merged_into_id"),
                result.getInt("sort_order"), result.getLong("revision"), aliases(id),
                result.getInt("question_count"), memberships(id));
    }

    private List<BookMembership> memberships(String id) {
        return jdbc.query("""
                SELECT b.id book_id,b.name book_name,c.id chapter_id,c.name chapter_name
                  FROM question_bank_knowledge bk
                  JOIN question_bank b ON b.id=bk.bank_id
                  JOIN question_bank_chapter c ON c.id=bk.chapter_id AND c.bank_id=bk.bank_id
                 WHERE bk.knowledge_point_id=? ORDER BY b.name,c.sort_order,c.id
                """, (result, row) -> new BookMembership(result.getString("book_id"),
                result.getString("book_name"), result.getString("chapter_id"),
                result.getString("chapter_name")), id);
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

    private static SqlFilter filter(String query, String subject, String section, String chapter, String status,
                                    String bookId, String chapterId, String membership) {
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
        if (bookId != null && !bookId.isBlank()) {
            clauses.add("EXISTS (SELECT 1 FROM question_bank_knowledge bk WHERE bk.knowledge_point_id=k.id AND bk.bank_id=?)");
            params.add(bookId.trim());
        }
        if (chapterId != null && !chapterId.isBlank()) {
            clauses.add("EXISTS (SELECT 1 FROM question_bank_knowledge bk WHERE bk.knowledge_point_id=k.id AND bk.chapter_id=?)");
            params.add(chapterId.trim());
        }
        if ("assigned".equals(membership)) {
            clauses.add("EXISTS (SELECT 1 FROM question_bank_knowledge bk WHERE bk.knowledge_point_id=k.id)");
        } else if ("unassigned".equals(membership)) {
            clauses.add("NOT EXISTS (SELECT 1 FROM question_bank_knowledge bk WHERE bk.knowledge_point_id=k.id)");
        }
        return new SqlFilter(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), params);
    }

    private int count(String table, String column, String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
                Integer.class, id);
        return count == null ? 0 : count;
    }

    private int countWhere(String sql, Object... params) {
        Integer count = jdbc.queryForObject(sql, Integer.class, params);
        return count == null ? 0 : count;
    }

    private static void add(List<String> clauses, List<Object> params, String column, String value) {
        if (value != null && !value.isBlank()) { clauses.add(column + " = ?"); params.add(value.trim()); }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Set<String> normalizeAliases(List<String> aliases) {
        Set<String> normalized = new LinkedHashSet<>();
        if (aliases == null) return normalized;
        for (String alias : aliases) if (alias != null && !alias.isBlank()) normalized.add(alias.trim());
        return normalized;
    }

    private void addAliasIfAbsent(String targetId, KnowledgeView target, String alias) {
        if (alias == null || alias.isBlank() || alias.equals(target.name()) || alias.equals(target.code())) return;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM knowledge_alias WHERE knowledge_point_id = ? AND alias = ?
                """, Integer.class, targetId, alias.trim());
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO knowledge_alias(id, knowledge_point_id, alias) VALUES (?, ?, ?)",
                    UUID.randomUUID().toString(), targetId, alias.trim());
        }
    }

    private void conflictOrMissing(String id) {
        if (find(id).isEmpty()) throw missing("知识点不存在。");
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "知识点已被其他人修改，请重新加载。");
    }

    private static org.springframework.web.server.ResponseStatusException missing(String message) {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, message);
    }

    private static void bad(String message) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, message);
    }

    private record RelationRow(String questionId, String role, int sortOrder) {}

    private record SqlFilter(String where, List<Object> params) {}
}

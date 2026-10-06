package cn.tihaishitu.manage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GlobalKnowledgeBatchImportService {
    public static final String SCHEMA_VERSION = "global-knowledge-batch/v2";
    public static final String LEGACY_SCHEMA_VERSION = "global-knowledge-batch/v1";
    private static final Set<String> ROLES = Set.of("core", "auxiliary");
    private static final Set<String> STATUSES = Set.of("active", "deprecated");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final KnowledgeManagementStore knowledgeStore;

    public GlobalKnowledgeBatchImportService(JdbcTemplate jdbc, ObjectMapper mapper,
                                             KnowledgeManagementStore knowledgeStore) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.knowledgeStore = knowledgeStore;
    }

    @Transactional
    public ImportResult importBatch(JsonNode document, String actorId) {
        ImportRequest request = parse(document);
        validate(request);
        BookInput book = request.book();
        boolean bookExists = count("SELECT COUNT(*) FROM question_bank WHERE id=?", book.id()) > 0;
        if (bookExists) {
            jdbc.update("""
                    UPDATE question_bank SET name=?,description=?,enabled=?,revision=revision+1,
                           updated_at=CURRENT_TIMESTAMP WHERE id=?
                    """, book.name().trim(), value(book.description()), book.enabled(), book.id());
        } else {
            jdbc.update("""
                    INSERT INTO question_bank(id,name,description,enabled,weight_value,revision)
                    VALUES (?,?,?,?,100,1)
                    """, book.id(), book.name().trim(), value(book.description()), book.enabled());
        }

        Map<String, String> chapterIds = new LinkedHashMap<>();
        Set<String> usedChapterCodes = request.knowledgePoints().stream()
                .map(point -> point.chapterCode().trim()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (ChapterInput chapter : request.chapters().stream().filter(item -> usedChapterCodes.contains(item.code().trim())).toList()) {
            List<String> existing = jdbc.query("SELECT id FROM question_bank_chapter WHERE bank_id=? AND chapter_code=?",
                    (row, index) -> row.getString("id"), book.id(), chapter.code().trim());
            chapterIds.put(chapter.code().trim(), existing.isEmpty()
                    ? UUID.nameUUIDFromBytes((book.id() + ":" + chapter.code().trim()).getBytes(StandardCharsets.UTF_8)).toString()
                    : existing.get(0));
        }
        for (ChapterInput chapter : request.chapters().stream().filter(item -> usedChapterCodes.contains(item.code().trim())).toList()) {
            String code = chapter.code().trim();
            String id = chapterIds.get(code);
            int changed = jdbc.update("""
                    UPDATE question_bank_chapter SET parent_id=?,name=?,description=?,sort_order=?,
                           revision=revision+1,updated_at=CURRENT_TIMESTAMP
                     WHERE bank_id=? AND chapter_code=?
                    """, null, chapter.name().trim(), value(chapter.description()), chapter.sortOrder(),
                    book.id(), code);
            if (changed == 0) {
                jdbc.update("""
                        INSERT INTO question_bank_chapter(
                            id,bank_id,parent_id,chapter_code,name,description,sort_order,revision)
                        VALUES (?,?,?,?,?,?,?,1)
                        """, id, book.id(), null, code, chapter.name().trim(),
                        value(chapter.description()), chapter.sortOrder());
            }
        }

        int created = 0, updated = 0, aliases = 0;
        Set<String> importedCodes = new LinkedHashSet<>();
        for (KnowledgeInput point : request.knowledgePoints()) {
            String code = point.code().trim();
            importedCodes.add(code);
            List<ExistingKnowledge> existing = jdbc.query("""
                    SELECT id,subject_name FROM global_knowledge_point WHERE code=?
                    """, (row, index) -> new ExistingKnowledge(row.getString("id"), row.getString("subject_name")), code);
            String id;
            if (existing.isEmpty()) {
                id = UUID.randomUUID().toString();
                String chapterName = request.chapters().stream()
                        .filter(chapter -> chapter.code().trim().equals(point.chapterCode().trim()))
                        .map(ChapterInput::name).findFirst().orElse("");
                jdbc.update("""
                        INSERT INTO global_knowledge_point(
                            id,code,name,subject_name,section_name,chapter_name,default_role,status,
                            description,explanation,introduced_version,sort_order,revision)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,1)
                        """, id, code, point.name().trim(), legacySubject(request), value(point.section()),
                        blank(point.chapter()) ? chapterName : point.chapter().trim(), point.defaultRole(), point.status(), value(point.description()),
                        value(point.explanation()), request.schemaVersion(), point.sortOrder());
                created++;
            } else {
                ExistingKnowledge current = existing.get(0);
                if (LEGACY_SCHEMA_VERSION.equals(request.schemaVersion())
                        && !current.subject().equals(request.subject().trim())) {
                    throw bad("知识点 code " + code + " 已属于另一学科，整批导入已取消。");
                }
                id = current.id();
                jdbc.update("""
                        UPDATE global_knowledge_point
                           SET name=?,section_name=?,chapter_name=?,default_role=?,status=?,description=?,
                               explanation=?,sort_order=?,revision=revision+1,updated_at=CURRENT_TIMESTAMP
                         WHERE id=?
                        """, point.name().trim(), value(point.section()), blank(point.chapter())
                                ? request.chapters().stream().filter(chapter -> chapter.code().trim().equals(point.chapterCode().trim()))
                                .map(ChapterInput::name).findFirst().orElse("") : point.chapter().trim(), point.defaultRole(),
                        point.status(), value(point.description()), value(point.explanation()), point.sortOrder(), id);
                updated++;
            }
            jdbc.update("DELETE FROM knowledge_alias WHERE knowledge_point_id=?", id);
            for (String alias : normalizeAliases(point.aliases())) {
                jdbc.update("INSERT INTO knowledge_alias(id,knowledge_point_id,alias) VALUES (?,?,?)",
                        UUID.randomUUID().toString(), id, alias);
                aliases++;
            }
            jdbc.update("""
                    INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order)
                    VALUES (?,?,?,?)
                    ON DUPLICATE KEY UPDATE chapter_id=VALUES(chapter_id),sort_order=VALUES(sort_order)
                    """, book.id(), id, chapterIds.get(point.chapterCode().trim()), point.sortOrder());
        }
        jdbc.update("UPDATE question_bank SET revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?", book.id());
        String importId = UUID.randomUUID().toString();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", request.schemaVersion());
        metadata.put("bookId", book.id());
        metadata.put("bookName", book.name());
        metadata.put("subject", legacySubject(request));
        metadata.put("chapterCount", request.chapters().size());
        metadata.put("knowledgePointCount", request.knowledgePoints().size());
        metadata.put("createdKnowledgePoints", created);
        metadata.put("updatedKnowledgePoints", updated);
        metadata.put("membershipCount", importedCodes.size());
        metadata.put("aliasCount", aliases);
        knowledgeStore.audit(actorId, "KNOWLEDGE_BATCH_IMPORTED", "knowledge_batch", importId, metadata);
        return new ImportResult(request.schemaVersion(), importId, book.id(), book.name(), legacySubject(request),
                usedChapterCodes.size(), request.knowledgePoints().size(), created, updated,
                importedCodes.size(), aliases);
    }

    private ImportRequest parse(JsonNode document) {
        if (document == null || !document.isObject()) throw bad("导入内容必须是 JSON 对象。");
        try { return mapper.treeToValue(document, ImportRequest.class); }
        catch (JsonProcessingException error) { throw bad("知识点批次 JSON 字段类型不正确。"); }
    }

    private void validate(ImportRequest request) {
        if (request == null || !Set.of(SCHEMA_VERSION, LEGACY_SCHEMA_VERSION).contains(request.schemaVersion()))
            throw bad("schemaVersion 必须为 " + SCHEMA_VERSION + "（旧 v1 仍兼容）。");
        if (request.book() == null || !uuid(request.book().id()) || blank(request.book().name())) throw bad("book 必须提供标准 UUID 和名称。");
        if (LEGACY_SCHEMA_VERSION.equals(request.schemaVersion()) && blank(request.subject())) throw bad("v1 subject 不能为空。");
        List<ChapterInput> chapters = request.chapters() == null ? List.of() : request.chapters();
        List<KnowledgeInput> points = request.knowledgePoints() == null ? List.of() : request.knowledgePoints();
        if (chapters.isEmpty() || points.isEmpty()) throw bad("章节和知识点都不能为空。");
        Set<String> chapterCodes = new LinkedHashSet<>();
        for (ChapterInput chapter : chapters) {
            if (chapter == null || blank(chapter.code()) || blank(chapter.name()) || !chapterCodes.add(chapter.code().trim())) throw bad("章节 code/name 非法或重复。");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (KnowledgeInput point : points) {
            if (point == null || blank(point.code()) || blank(point.name())
                    || blank(point.chapterCode()) || !codes.add(point.code().trim())) throw bad("知识点 code/name/chapterCode 非法或重复。");
            if (!chapterCodes.contains(point.chapterCode().trim())) throw bad("知识点引用了不存在的 chapterCode：" + point.chapterCode());
            if (!ROLES.contains(point.defaultRole()) || !STATUSES.contains(point.status())) throw bad("知识点 defaultRole 或 status 不合法。");
        }
    }

    private long count(String sql, Object... params) { Long value = jdbc.queryForObject(sql, Long.class, params); return value == null ? 0 : value; }
    private static String legacySubject(ImportRequest request) {
        return blank(request.subject()) ? request.book().name().trim() : request.subject().trim();
    }
    private static boolean uuid(String value) { try { UUID.fromString(value); return true; } catch (RuntimeException error) { return false; } }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static Set<String> normalizeAliases(List<String> values) { Set<String> result = new LinkedHashSet<>(); if (values != null) values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(result::add); return result; }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record ImportRequest(String schemaVersion, BookInput book, String subject,
                                List<ChapterInput> chapters, List<KnowledgeInput> knowledgePoints) {}
    public record BookInput(String id, String name, String description, boolean enabled) {}
    public record ChapterInput(String code, String name, String parentCode, String description, int sortOrder) {}
    public record KnowledgeInput(String code, String name, String section, String chapter, String chapterCode,
                                 String defaultRole, String status, String description, String explanation,
                                 int sortOrder, List<String> aliases) {}
    private record ExistingKnowledge(String id, String subject) {}
    public record ImportResult(String schemaVersion, String importId, String bookId, String bookName, String subject,
                               int chapterCount, int knowledgePointCount, int createdKnowledgePoints,
                               int updatedKnowledgePoints, int membershipCount, int aliasCount) {}
}

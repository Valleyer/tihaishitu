package cn.tihaishitu.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class Math1KnowledgeSeed implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Resource resource;
    private final boolean enabled;

    public Math1KnowledgeSeed(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            @Value("${app.knowledge.math1-resource:classpath:knowledge/math1-knowledge-final.json}") Resource resource,
            @Value("${app.knowledge.math1-seed-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.resource = resource;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) return;
        SeedDocument document = mapper.readValue(resource.getInputStream(), SeedDocument.class);
        validate(document);
        String version = String.valueOf(document.metadata().getOrDefault("version", "math1-final"));
        int order = 0;
        for (SeedPoint point : document.points()) {
            upsert(point, version, order++);
        }
    }

    private void upsert(SeedPoint point, String version, int order) {
        List<String> ids = jdbc.query(
                "SELECT id FROM global_knowledge_point WHERE code = ?",
                (result, row) -> result.getString("id"), point.code());
        String id = ids.isEmpty() ? stableUuid("knowledge:" + point.code()) : ids.get(0);
        if (ids.isEmpty()) {
            jdbc.update("""
                    INSERT INTO global_knowledge_point(
                        id, code, name, subject_name, section_name, chapter_name,
                        default_role, status, description, explanation,
                        introduced_version, sort_order, revision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    """, id, point.code(), point.name(), point.subject(), point.section(), point.chapter(),
                    point.defaultRole(), point.status(), "", "", version, order);
        } else {
            jdbc.update("""
                    UPDATE global_knowledge_point
                       SET name = ?, subject_name = ?, section_name = ?, chapter_name = ?,
                           default_role = ?, status = ?, introduced_version = ?, sort_order = ?,
                           revision = revision + CASE
                               WHEN name <> ? OR subject_name <> ? OR section_name <> ? OR chapter_name <> ?
                                 OR default_role <> ? OR status <> ? OR sort_order <> ? THEN 1 ELSE 0 END
                     WHERE id = ?
                    """, point.name(), point.subject(), point.section(), point.chapter(),
                    point.defaultRole(), point.status(), version, order,
                    point.name(), point.subject(), point.section(), point.chapter(),
                    point.defaultRole(), point.status(), order, id);
        }

        jdbc.update("DELETE FROM knowledge_alias WHERE knowledge_point_id = ?", id);
        for (String alias : point.aliases()) {
            if (alias == null || alias.isBlank()) continue;
            jdbc.update("""
                    INSERT INTO knowledge_alias(id, knowledge_point_id, alias)
                    VALUES (?, ?, ?)
                    """, stableUuid("knowledge-alias:" + point.code() + ":" + alias.trim()), id, alias.trim());
        }
    }

    private static void validate(SeedDocument document) {
        if (document.points() == null || document.points().size() != 469) {
            throw new IllegalStateException("数学一知识点正式源必须恰好包含 469 条记录");
        }
        long uniqueCodes = document.points().stream().map(SeedPoint::code).distinct().count();
        if (uniqueCodes != 469) throw new IllegalStateException("数学一知识点 code 存在重复");
        Map<String, Long> counts = document.points().stream().collect(
                java.util.stream.Collectors.groupingBy(SeedPoint::section, java.util.stream.Collectors.counting()));
        if (!Long.valueOf(198).equals(counts.get("高等数学"))
                || !Long.valueOf(136).equals(counts.get("线性代数"))
                || !Long.valueOf(135).equals(counts.get("概率论与数理统计"))) {
            throw new IllegalStateException("数学一知识点分科数量与正式源约束不符");
        }
    }

    private static String stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record SeedDocument(
            Map<String, Object> metadata,
            @JsonProperty("knowledge_points") List<SeedPoint> points) {}

    public record SeedPoint(
            @JsonProperty("id") String code,
            String name,
            List<String> aliases,
            String subject,
            String section,
            String chapter,
            @JsonProperty("default_role") String defaultRole,
            String status) {}
}

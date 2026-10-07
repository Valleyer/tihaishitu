package cn.tihaishitu.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OfficialMath1BookBootstrap {
    public static final String BOOK_ID = "628a3d64-c820-4d1f-b482-8e2715bb7cf2";

    private static final Logger log = LoggerFactory.getLogger(OfficialMath1BookBootstrap.class);
    private static final String BOOK_NAME = "数学一";
    private static final String LEGACY_BOOK_NAME = "2026年考研数学一真题";
    private static final String BOOK_DESCRIPTION = "万境求知官方维护的一站式数学一学习书籍。";
    /** 历史品牌占位说明；只有仍是这些占位值时才幂等升级，管理员自写说明永不覆盖。 */
    private static final List<String> LEGACY_BOOK_DESCRIPTIONS = List.of(
            "题海仕途官方维护的一站式数学一学习书籍。",
            "知境官方维护的一站式数学一学习书籍。");
    private static final Pattern KNOWLEDGE_CODE = Pattern.compile("^(M1-[HLP]\\d{2})-\\d{3}$");
    private static final List<ChapterDefinition> CHAPTERS = List.of(
            chapter("M1-H", "高等数学", null, 0),
            chapter("M1-H01", "函数、极限与连续", "M1-H", 0),
            chapter("M1-H02", "一元函数微分学", "M1-H", 1),
            chapter("M1-H03", "一元函数积分学", "M1-H", 2),
            chapter("M1-H04", "向量代数与空间解析几何", "M1-H", 3),
            chapter("M1-H05", "多元函数微分学", "M1-H", 4),
            chapter("M1-H06", "多元函数积分学", "M1-H", 5),
            chapter("M1-H07", "无穷级数", "M1-H", 6),
            chapter("M1-H08", "常微分方程", "M1-H", 7),
            chapter("M1-L", "线性代数", null, 1),
            chapter("M1-L01", "行列式", "M1-L", 0),
            chapter("M1-L02", "矩阵", "M1-L", 1),
            chapter("M1-L03", "向量与向量空间", "M1-L", 2),
            chapter("M1-L04", "线性方程组", "M1-L", 3),
            chapter("M1-L05", "特征值与特征向量", "M1-L", 4),
            chapter("M1-L06", "二次型", "M1-L", 5),
            chapter("M1-P", "概率论与数理统计", null, 2),
            chapter("M1-P01", "随机事件和概率", "M1-P", 0),
            chapter("M1-P02", "一维随机变量及其分布", "M1-P", 1),
            chapter("M1-P03", "多维随机变量及其分布", "M1-P", 2),
            chapter("M1-P04", "随机变量的数字特征", "M1-P", 3),
            chapter("M1-P05", "大数定律与中心极限定理", "M1-P", 4),
            chapter("M1-P06", "数理统计基本概念", "M1-P", 5),
            chapter("M1-P07", "参数估计", "M1-P", 6),
            chapter("M1-P08", "假设检验", "M1-P", 7)
    );

    private final JdbcTemplate jdbc;

    public OfficialMath1BookBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(300)
    @Transactional
    public void bootstrapAfterCatalogMigration() {
        ensureBook();
        Map<String, String> chapterIds = ensureChapters();
        int memberships = ensureKnowledgeMemberships(chapterIds);
        verifyCanonicalMemberships(chapterIds, memberships);
        removeLegacyChapters(chapterIds);
        verifyCanonicalStructure();
        log.info("官方《数学一》底座已就绪：{} 个章节，{} 个知识点。",
                chapterIds.size(), memberships);
    }

    private void ensureBook() {
        List<String> names = jdbc.query("SELECT name FROM question_bank WHERE id = ?",
                (result, row) -> result.getString("name"), BOOK_ID);
        if (names.isEmpty()) {
            jdbc.update("""
                    INSERT INTO question_bank(id, name, description, enabled, weight_value, revision)
                    VALUES (?, ?, ?, TRUE, 1, 1)
                    """, BOOK_ID, BOOK_NAME, BOOK_DESCRIPTION);
            return;
        }
        if (LEGACY_BOOK_NAME.equals(names.get(0))) {
            jdbc.update("""
                    UPDATE question_bank
                       SET name = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND name = ?
                    """, BOOK_NAME, BOOK_ID, LEGACY_BOOK_NAME);
        }
        for (String legacyDescription : LEGACY_BOOK_DESCRIPTIONS) {
            jdbc.update("""
                    UPDATE question_bank
                       SET description = ?, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND description = ?
                    """, BOOK_DESCRIPTION, BOOK_ID, legacyDescription);
        }
    }

    private Map<String, String> ensureChapters() {
        jdbc.update("UPDATE question_bank_chapter SET parent_id=NULL WHERE bank_id=? AND parent_id IS NOT NULL", BOOK_ID);
        jdbc.update("DELETE FROM question_bank_chapter WHERE bank_id=? AND chapter_code IN ('M1-H','M1-L','M1-P') "
                + "AND NOT EXISTS (SELECT 1 FROM question_bank_knowledge membership WHERE membership.chapter_id=question_bank_chapter.id)", BOOK_ID);
        Map<String, String> ids = new LinkedHashMap<>();
        int flatOrder = 0;
        for (ChapterDefinition chapter : CHAPTERS.stream().filter(item -> item.parentCode() != null).toList()) {
            List<String> existing = jdbc.query("""
                    SELECT id FROM question_bank_chapter WHERE bank_id = ? AND chapter_code = ?
                    """, (result, row) -> result.getString("id"), BOOK_ID, chapter.code());
            String id;
            if (existing.isEmpty()) {
                id = stableUuid("book-chapter:" + BOOK_ID + ":" + chapter.code());
                jdbc.update("""
                        INSERT INTO question_bank_chapter(
                            id, bank_id, parent_id, chapter_code, name, description, sort_order, revision)
                        VALUES (?, ?, ?, ?, ?, '', ?, 1)
                        """, id, BOOK_ID, null, chapter.code(), chapter.name(), flatOrder);
            } else {
                id = existing.get(0);
                jdbc.update("UPDATE question_bank_chapter SET parent_id=NULL,sort_order=? WHERE id=?", flatOrder, id);
            }
            ids.put(chapter.code(), id);
            flatOrder++;
        }
        return ids;
    }

    private int ensureKnowledgeMemberships(Map<String, String> chapterIds) {
        List<KnowledgeRow> points = jdbc.query("""
                SELECT id, code, sort_order
                  FROM global_knowledge_point
                 WHERE subject_name = '数学一' AND status = 'active' AND code LIKE 'M1-%'
                 ORDER BY sort_order, code
                """, (result, row) -> new KnowledgeRow(result.getString("id"), result.getString("code"),
                result.getInt("sort_order")));
        if (points.size() != 469) {
            throw new IllegalStateException("官方《数学一》必须包含 469 个 active M1 知识点，当前为 " + points.size());
        }
        for (KnowledgeRow point : points) {
            String chapterCode = chapterCode(point.code());
            String chapterId = chapterIds.get(chapterCode);
            if (chapterId == null) {
                throw new IllegalStateException("数学一知识点无法匹配章节：" + point.code());
            }
            int changed = jdbc.update("""
                    UPDATE question_bank_knowledge SET chapter_id=?,sort_order=?
                     WHERE bank_id=? AND knowledge_point_id=?
                    """, chapterId, point.sortOrder(), BOOK_ID, point.id());
            if (changed == 0) {
                jdbc.update("""
                        INSERT INTO question_bank_knowledge(
                            bank_id, knowledge_point_id, chapter_id, sort_order)
                        VALUES (?, ?, ?, ?)
                        """, BOOK_ID, point.id(), chapterId, point.sortOrder());
            }
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?", Integer.class, BOOK_ID);
        return count == null ? 0 : count;
    }

    private void removeLegacyChapters(Map<String, String> chapterIds) {
        if (chapterIds.isEmpty()) return;
        String marks = String.join(",", java.util.Collections.nCopies(chapterIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(BOOK_ID);
        args.addAll(chapterIds.values());
        jdbc.update("""
                DELETE FROM question_bank_chapter WHERE bank_id=? AND id NOT IN (%s)
                  AND NOT EXISTS (SELECT 1 FROM question_bank_knowledge membership
                                   WHERE membership.chapter_id=question_bank_chapter.id)
                """.formatted(marks), args.toArray());
    }

    private void verifyCanonicalMemberships(Map<String, String> chapterIds, int memberships) {
        String marks = String.join(",", java.util.Collections.nCopies(chapterIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(BOOK_ID);
        args.addAll(chapterIds.values());
        Integer misplaced = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id=? AND chapter_id NOT IN (" + marks + ")",
                Integer.class, args.toArray());
        if (memberships != 469 || misplaced == null || misplaced != 0) {
            throw new IllegalStateException("官方《数学一》的 469 个 membership 必须全部归入 canonical Chapter。");
        }
    }

    private void verifyCanonicalStructure() {
        Integer chapters = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_chapter WHERE bank_id=?",
                Integer.class, BOOK_ID);
        Integer nested = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_chapter WHERE bank_id=? AND parent_id IS NOT NULL",
                Integer.class, BOOK_ID);
        if (chapters == null || chapters != 22 || nested == null || nested != 0) {
            throw new IllegalStateException("官方《数学一》必须包含 22 个扁平 canonical Chapter。");
        }
    }

    private static String chapterCode(String knowledgeCode) {
        String code = chapterCodeOrNull(knowledgeCode);
        if (code == null) throw new IllegalStateException("无效的数学一知识点 code：" + knowledgeCode);
        return code;
    }

    private static String chapterCodeOrNull(String knowledgeCode) {
        Matcher matcher = KNOWLEDGE_CODE.matcher(knowledgeCode == null ? "" : knowledgeCode);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static ChapterDefinition chapter(String code, String name, String parentCode, int sortOrder) {
        return new ChapterDefinition(code, name, parentCode, sortOrder);
    }

    private static String stableUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record ChapterDefinition(String code, String name, String parentCode, int sortOrder) {}
    private record KnowledgeRow(String id, String code, int sortOrder) {}
}

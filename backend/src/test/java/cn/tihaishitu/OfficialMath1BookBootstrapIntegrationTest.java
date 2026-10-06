package cn.tihaishitu;

import cn.tihaishitu.catalog.CatalogStore;
import cn.tihaishitu.catalog.OfficialMath1BookBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:official-math1-book;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.seed-enabled=false"
})
@Transactional
class OfficialMath1BookBootstrapIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired OfficialMath1BookBootstrap bootstrap;
    @Autowired CatalogStore catalog;

    @Test
    void createsStableOfficialBookChaptersAndCompleteKnowledgeScopeIdempotently() {
        assertThat(jdbc.queryForObject("SELECT name FROM question_bank WHERE id = ?", String.class,
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo("数学一");
        assertThat(count("SELECT COUNT(*) FROM question_bank_chapter WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(22);
        assertThat(count("SELECT COUNT(*) FROM question_bank_chapter WHERE bank_id = ? AND parent_id IS NULL",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(22);
        assertThat(count("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(469);
        assertThat(catalog.loadBookKnowledgePoints(OfficialMath1BookBootstrap.BOOK_ID)).hasSize(469);
        List<String> chapterIds = jdbc.query("""
                SELECT id FROM question_bank_chapter WHERE bank_id = ? ORDER BY chapter_code
                """, (result, row) -> result.getString("id"), OfficialMath1BookBootstrap.BOOK_ID);
        jdbc.update("UPDATE question_bank SET description = '管理员补充说明' WHERE id = ?",
                OfficialMath1BookBootstrap.BOOK_ID);
        jdbc.update("UPDATE question_bank_chapter SET name = '管理员章节名' WHERE bank_id = ? AND chapter_code = 'M1-H01'",
                OfficialMath1BookBootstrap.BOOK_ID);

        bootstrap.bootstrapAfterCatalogMigration();

        assertThat(jdbc.query("""
                SELECT id FROM question_bank_chapter WHERE bank_id = ? ORDER BY chapter_code
                """, (result, row) -> result.getString("id"), OfficialMath1BookBootstrap.BOOK_ID))
                .containsExactlyElementsOf(chapterIds);
        assertThat(count("SELECT COUNT(*) FROM question_bank_chapter WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(22);
        assertThat(count("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(469);
        assertThat(jdbc.queryForObject("SELECT description FROM question_bank WHERE id = ?", String.class,
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo("管理员补充说明");
        assertThat(jdbc.queryForObject("SELECT name FROM question_bank_chapter WHERE bank_id = ? AND chapter_code = 'M1-H01'",
                String.class, OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo("管理员章节名");
    }

    @Test
    void upgradesLegacyOfficialBookNameWithoutReplacingItsMetadata() {
        jdbc.update("DELETE FROM question_bank WHERE id = ?", OfficialMath1BookBootstrap.BOOK_ID);
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE subject_name = '数学一' AND code LIKE 'M1-%'"))
                .isEqualTo(469);
        jdbc.update("""
                INSERT INTO question_bank(id, name, description, enabled, weight_value, revision)
                VALUES (?, '2026年考研数学一真题', '保留的旧说明', TRUE, 5, 7)
                """, OfficialMath1BookBootstrap.BOOK_ID);

        bootstrap.bootstrapAfterCatalogMigration();

        assertThat(jdbc.queryForObject("SELECT name FROM question_bank WHERE id = ?", String.class,
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo("数学一");
        assertThat(jdbc.queryForObject("SELECT description FROM question_bank WHERE id = ?", String.class,
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo("保留的旧说明");
        assertThat(jdbc.queryForObject("SELECT weight_value FROM question_bank WHERE id = ?", Integer.class,
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM question_bank_chapter WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(22);
        assertThat(count("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(469);
    }

    @Test
    void preservesExisting2026QuestionWithoutAddingBookChapterSemantics() {
        String questionId = "e4659121-9b1a-405e-aad3-f9975c3fe50b";
        String knowledgeId = jdbc.queryForObject(
                "SELECT id FROM global_knowledge_point WHERE code = 'M1-H01-001'", String.class);
        insertQuestion(questionId, "pending_review", "2026 原题内容");
        jdbc.update("""
                INSERT INTO question_resource_knowledge(question_id, knowledge_point_id, relation_role, sort_order)
                VALUES (?, ?, 'core', 0)
                """, questionId, knowledgeId);
        jdbc.update("INSERT INTO question_bank_item(bank_id, question_id, sort_order) VALUES (?, ?, 0)",
                OfficialMath1BookBootstrap.BOOK_ID, questionId);

        bootstrap.bootstrapAfterCatalogMigration();

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", questionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content_markdown FROM question_resource WHERE id = ?",
                String.class, questionId)).isEqualTo("2026 原题内容");
        assertThat(count("SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ? AND question_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID, questionId)).isEqualTo(1);
    }

    @Test
    void knowledgeCanBeSharedByAnotherBookWithoutCopyingGlobalResources() {
        String secondBook = UUID.randomUUID().toString();
        String secondRoot = UUID.randomUUID().toString();
        String secondChapter = UUID.randomUUID().toString();
        String questionId = UUID.randomUUID().toString();
        String knowledgeId = jdbc.queryForObject(
                "SELECT id FROM global_knowledge_point WHERE code = 'M1-L02-001'", String.class);
        insertQuestion(questionId, "published", "共享资源题");
        jdbc.update("INSERT INTO question_resource_knowledge(question_id, knowledge_point_id, relation_role, sort_order) VALUES (?, ?, 'core', 0)",
                questionId, knowledgeId);
        jdbc.update("INSERT INTO question_bank(id, name, description, enabled, weight_value) VALUES (?, '第二本书', '', TRUE, 1)", secondBook);
        jdbc.update("""
                INSERT INTO question_bank_chapter(id, bank_id, chapter_code, name, description, sort_order)
                VALUES (?, ?, 'CUSTOM', '自定义目录', '', 0)
                """, secondRoot, secondBook);
        jdbc.update("""
                INSERT INTO question_bank_chapter(id, bank_id, parent_id, chapter_code, name, description, sort_order)
                VALUES (?, ?, ?, 'CUSTOM-01', '自定义章节', '', 0)
                """, secondChapter, secondBook, secondRoot);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id, knowledge_point_id, chapter_id, sort_order) VALUES (?, ?, ?, 0)",
                secondBook, knowledgeId, secondChapter);

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", questionId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE id = ?", knowledgeId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM question_bank_knowledge WHERE knowledge_point_id = ?", knowledgeId)).isEqualTo(2);

        jdbc.update("DELETE FROM question_bank WHERE id = ?", secondBook);

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", questionId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM global_knowledge_point WHERE id = ?", knowledgeId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM question_bank_knowledge WHERE knowledge_point_id = ?", knowledgeId)).isEqualTo(1);
    }

    private void insertQuestion(String id, String status, String content) {
        jdbc.update("""
                INSERT INTO question_resource(
                    id, subject_name, source_type, source_name, exam_year, question_number,
                    question_type, presentation_type, grading_mode, content_markdown,
                    standard_answer_json, analysis_markdown, difficulty, status, revision)
                VALUES (?, '数学一', 'real_exam', '2026年全国硕士研究生招生考试数学一', 2026, '1',
                        'single_choice', 'single_choice', 'auto', ?, '"A"', '', 2, ?, 1)
                """, id, content, status);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}

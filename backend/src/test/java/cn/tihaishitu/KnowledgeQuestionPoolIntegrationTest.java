package cn.tihaishitu;

import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:knowledge-question-pool;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class KnowledgeQuestionPoolIntegrationTest {
    @Autowired KnowledgeQuestionPoolService pool;
    @Autowired JdbcTemplate jdbc;

    @Test
    void v2QuestionIsPlayableWithoutBookQuestionRelation() {
        String point = knowledge("K1");
        String book = book("Book B", point);
        String question = question("Q1", 3, relation(point, "core"));

        var plan = pool.planKnowledgePoints(Set.of(book), 1);
        var selected = pool.selectQuestion(request(point, plan.allowedKnowledgePointIds(), Set.of(), false));

        assertThat(plan.knowledgePointIds()).containsExactly(point);
        assertThat(selected.id()).isEqualTo(question);
        assertThat(count("SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ? AND question_id = ?",
                book, question)).isZero();
    }

    @Test
    void candidateCoversCoreAndAuxiliaryWithoutRequiringDependencies() {
        String pointA = knowledge("A");
        String pointB = knowledge("B");
        String pointC = knowledge("C");
        String coreA = question("core A", 3, relation(pointA, "core"));
        String auxiliaryA = question("A only auxiliary", 2, relation(pointA, "auxiliary"), relation(pointB, "core"));
        String outsideDependency = question("outside dependency", 2,
                relation(pointA, "core"), relation(pointC, "auxiliary"));

        List<String> candidates = pool.eligibleQuestions(
                        request(pointA, Set.of(pointA, pointB), Set.of(), false)).stream()
                .map(question -> question.id()).toList();

        // 最新规则：core 与 auxiliary 都算该知识点的专项候选；
        // 题目关联的其他知识点即使不在 allowed scope 内，也不再阻止发题。
        assertThat(candidates).containsExactlyInAnyOrder(coreA, auxiliaryA, outsideDependency);
    }

    @Test
    void legacyBlankIsNeverPlayable() {
        String point = knowledge("legacy blank guard");
        String book = book("Legacy Blank Book", point);
        question("legacy blank", 2, "blank", relation(point, "core"));
        String solution = question("supported solution", 3, relation(point, "core"));

        var plan = pool.planKnowledgePoints(Set.of(book), 1);
        assertThat(pool.eligibleQuestions(request(point, plan.allowedKnowledgePointIds(), Set.of(), false)))
                .extracting(question -> question.id())
                .containsExactly(solution);
    }

    @Test
    void multipleBooksDeduplicateScopeAndSeenQuestionsNeverRepeat() {
        String point = knowledge("shared");
        String firstBook = book("Book A", point);
        String secondBook = book("Book B", point);
        String hard = question("hard", 4, relation(point, "core"));
        String simple = question("simple", 2, relation(point, "core"));

        var plan = pool.planKnowledgePoints(Set.of(firstBook, secondBook), 1);
        assertThat(plan.allowedKnowledgePointIds()).containsExactly(point);
        assertThat(plan.knowledgePointIds()).containsExactly(point);

        assertThat(pool.selectQuestion(request(point, plan.allowedKnowledgePointIds(), Set.of(), true)).id())
                .isEqualTo(simple);
        assertThat(pool.selectQuestion(request(point, plan.allowedKnowledgePointIds(), Set.of(simple), true)).id())
                .isEqualTo(hard);
        assertThatThrownBy(() -> pool.selectQuestion(
                request(point, plan.allowedKnowledgePointIds(), Set.of(simple, hard), false)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("可用题目已用尽");
    }

    private KnowledgeQuestionPoolService.QuestionPoolRequest request(
            String point, Set<String> allowed, Set<String> seen, boolean training) {
        return new KnowledgeQuestionPoolService.QuestionPoolRequest(
                point, allowed, seen, null, training
                ? KnowledgeQuestionPoolService.Mode.TRAINING
                : KnowledgeQuestionPoolService.Mode.NORMAL);
    }

    private String knowledge(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(
                    id, code, name, subject_name, section_name, chapter_name, default_role,
                    status, description, explanation, introduced_version, sort_order, revision)
                VALUES (?, ?, ?, '测试科目', '测试分部', '测试章节', 'core',
                        'active', '', '', 'phase-c-test', 0, 1)
                """, id, "TEST-" + id, name);
        return id;
    }

    private String book(String name, String pointId) {
        String bookId = UUID.randomUUID().toString();
        String chapterId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_bank(id, name, description, enabled, weight_value, revision)
                VALUES (?, ?, '', TRUE, 1, 1)
                """, bookId, name);
        jdbc.update("""
                INSERT INTO question_bank_chapter(
                    id, bank_id, parent_id, chapter_code, name, description, sort_order, revision)
                VALUES (?, ?, NULL, 'TEST', '测试章节', '', 0, 1)
                """, chapterId, bookId);
        jdbc.update("""
                INSERT INTO question_bank_knowledge(bank_id, knowledge_point_id, chapter_id, sort_order)
                VALUES (?, ?, ?, 0)
                """, bookId, pointId, chapterId);
        return bookId;
    }

    private String question(String content, int difficulty, Relation... relations) {
        return question(content, difficulty, "solution", relations);
    }

    private String question(String content, int difficulty, String type, Relation... relations) {
        String id = UUID.randomUUID().toString();
        String presentation = "solution".equals(type) || "blank".equals(type)
                ? "self_assessment" : type;
        String grading = "self_assessment".equals(presentation) ? "self_assessment" : "auto";
        jdbc.update("""
                INSERT INTO question_resource(
                    id, subject_name, source_type, source_name, question_type, presentation_type,
                    grading_mode, content_markdown, standard_answer_json, analysis_markdown,
                    difficulty, status, revision)
                VALUES (?, '测试科目', 'custom', 'Phase C 测试', ?, ?,
                        ?, ?, '"答案"', '测试解析', ?, 'published', 1)
                """, id, type, presentation, grading, content, difficulty);
        for (int index = 0; index < relations.length; index++) {
            Relation relation = relations[index];
            jdbc.update("""
                    INSERT INTO question_resource_knowledge(
                        question_id, knowledge_point_id, relation_role, sort_order)
                    VALUES (?, ?, ?, ?)
                    """, id, relation.pointId(), relation.role(), index);
        }
        return id;
    }

    private Relation relation(String pointId, String role) {
        return new Relation(pointId, role);
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private record Relation(String pointId, String role) {}
}

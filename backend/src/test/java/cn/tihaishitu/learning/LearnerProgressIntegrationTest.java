package cn.tihaishitu.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-progress;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerProgressIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired LearnerKnowledgeStateStore states;
    @Autowired LearnerProgressService progress;
    @Autowired ObjectMapper mapper;

    @Test
    void deduplicatesOverallScopeUsesEffectiveBandsAndReturnsFlatChapters() {
        String learner = learner();
        String shared = knowledge("共享知识点"), decayed = knowledge("已衰减知识点");
        String ready = knowledge("基本掌握知识点"), proficient = knowledge("熟练知识点");
        BookFixture first = book("文集甲");
        String childA = chapter(first.id(), first.root(), "A", "子章甲", 1);
        String childB = chapter(first.id(), first.root(), "B", "子章乙", 2);
        member(first.id(), childA, shared, 0); member(first.id(), childB, decayed, 1);
        member(first.id(), childB, ready, 2);
        BookFixture second = book("文集乙");
        member(second.id(), second.root(), shared, 0); member(second.id(), second.root(), proficient, 1);
        select(learner, first.id()); select(learner, second.id());

        saveMastery(learner, shared, 20, 365, NOW);
        saveMastery(learner, decayed, 60, 10, NOW.minusSeconds(10 * 86_400L));
        saveMastery(learner, ready, 75, 365, NOW);
        saveMastery(learner, proficient, 100, 365, NOW);

        LearnerProgressService.ProgressView view = progress.progressAt(learner, NOW);

        assertThat(view.summary().totalKnowledgePoints()).isEqualTo(4);
        assertThat(view.summary().startedKnowledgePoints()).isEqualTo(4);
        assertThat(view.summary().readyKnowledgePoints()).isEqualTo(2);
        assertThat(view.summary().proficientKnowledgePoints()).isEqualTo(1);
        assertThat(view.bands()).containsEntry("unmastered", 1).containsEntry("learning", 1)
                .containsEntry("ready", 1).containsEntry("proficient", 1).containsEntry("unstarted", 0);
        assertThat(view.books()).extracting(LearnerProgressService.BookProgress::totalKnowledgePoints)
                .containsExactlyInAnyOrder(3, 2);
        LearnerProgressService.BookProgress firstView = view.books().stream()
                .filter(item -> item.bookId().equals(first.id())).findFirst().orElseThrow();
        assertThat(firstView.chapters()).extracting(LearnerProgressService.ChapterProgress::total)
                .containsExactly(0, 1, 2);
        assertThat(firstView.masteryProgress()).isGreaterThan(0).isLessThan(100);
        assertThat(firstView.chapters()).allMatch(chapter -> chapter.total() == 0
                ? chapter.masteryProgress() == 0
                : chapter.masteryProgress() > 0 && chapter.masteryProgress() < 100);
    }

    @Test
    void ordersRecentContactsByEffectiveContactAndKeepsMasteryEvidenceSeparate() {
        String learner = learner();
        String revealed = knowledge("仅查看答案"), olderGraded = knowledge("较早评分"), newerGraded = knowledge("最近评分");
        BookFixture book = book("最近接触文集");
        member(book.id(), book.root(), revealed, 0);
        member(book.id(), book.root(), olderGraded, 1);
        member(book.id(), book.root(), newerGraded, 2);
        select(learner, book.id());
        String question = question();
        String practice = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,status,revision) VALUES (?,?,'knowledge_drill',?,'active',1)",
                practice, learner, newerGraded);

        // 仅 reveal：产生「有效接触」，但没有 Mastery Evidence。
        reveal(learner, question, revealed, NOW.minusSeconds(60));
        // 真实评分：既有 Mastery Evidence，也有有效接触时间。
        attempt(learner, null, practice, question, olderGraded, "graded",
                NOW.minusSeconds(3 * 86_400L), "correct");
        attempt(learner, "ancient-official", null, question, newerGraded, "graded",
                NOW.minusSeconds(86_400L), "wrong");
        saveMastery(learner, olderGraded, 40, 10, NOW.minusSeconds(3 * 86_400L));
        saveMastery(learner, newerGraded, 40, 10, NOW.minusSeconds(86_400L));

        LearnerProgressService.ProgressView view = progress.progressAt(learner, NOW);

        // 按「最近有效接触时间」倒序，仅查看答案的知识点同样出现，并且只暴露正式目录路径。
        assertThat(view.recentContacts())
                .extracting(LearnerProgressService.RecentContact::knowledgePointId)
                .containsExactly(revealed, newerGraded, olderGraded);
        assertThat(view.recentContacts()).allSatisfy(contact ->
                assertThat(contact.bookName()).isEqualTo("最近接触文集"));

        // 仅 reveal 的知识点：有效接触时间非空，但 Mastery 证据时间与证据数保持为空 / 0。
        LearnerProgressService.RecentContact revealedOnly = view.recentContacts().get(0);
        assertThat(revealedOnly.lastOutcomeRevealedOnly()).isTrue();
        assertThat(revealedOnly.lastGraded()).isFalse();
        assertThat(revealedOnly.evidenceCount()).isZero();
        assertThat(revealedOnly.lastEvidenceAt()).isNull();
        assertThat(revealedOnly.lastEffectiveContactAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(revealedOnly.band()).isEqualTo("unstarted");

        // 真实评分的知识点：两个时间概念都存在且互不替代。
        LearnerProgressService.RecentContact graded = view.recentContacts().get(1);
        assertThat(graded.lastOutcomeRevealedOnly()).isFalse();
        assertThat(graded.lastGraded()).isTrue();
        // evidenceCount 由 Mastery 事实派生，真实评分后必须是有证据的；具体条数不属于本测试关注点。
        assertThat(graded.evidenceCount()).isPositive();
        assertThat(graded.lastEffectiveContactAt()).isEqualTo(NOW.minusSeconds(86_400L));
        assertThat(graded.lastEvidenceAt()).isNotNull();

        // 兼容的 graded-only recent：统计 7 天内的两个真实评分知识点，不包含仅 reveal 的那个。
        assertThat(view.recent().gradedAttempts7d()).isEqualTo(2);
        assertThat(view.recent().distinctKnowledgePoints7d()).isEqualTo(2);
        assertThat(view.recent().daily()).hasSize(7);
    }

    @Test
    void reusesReviewAndWrongQueueSemanticsWithoutNegativeRateFields() throws Exception {
        String learner = learner(), point = knowledge("需要巩固");
        BookFixture book = book("正向进度"); member(book.id(), book.root(), point, 0); select(learner, book.id());
        saveMastery(learner, point, 90, 10, NOW.minusSeconds(4 * 86_400L));
        String question = jdbc.queryForObject("""
                SELECT question_id FROM question_resource_knowledge
                 WHERE knowledge_point_id=? ORDER BY question_id LIMIT 1
                """, String.class, point);
        String wrongAttempt = attempt(learner, "ancient-official", null, question, point,
                "graded", NOW.minusSeconds(600), "wrong");
        jdbc.update("""
                INSERT INTO learner_wrong_question(learner_id,question_id,target_knowledge_point_id,
                    first_wrong_at,last_wrong_at,last_wrong_attempt_id,status)
                VALUES (?,?,?,?,?,?,'active')
                """, learner, question, point, Timestamp.from(NOW.minusSeconds(600)),
                Timestamp.from(NOW.minusSeconds(600)), wrongAttempt);

        LearnerProgressService.ProgressView view = progress.progressAt(learner, NOW);
        String json = mapper.writeValueAsString(view);

        assertThat(view.summary().reviewDue()).isEqualTo(1);
        assertThat(view.summary().wrongQuestions()).isEqualTo(1);
        assertThat(json).contains("wrongQuestions").doesNotContain("accuracy", "errorRate", "failureCount", "wrongCount");
    }

    private String learner() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                id, "progress-" + id, "进度学习者");
        jdbc.update("INSERT INTO learner_study_profile(learner_id,pace,difficulty,focus_mode,revision) VALUES (?,'normal','standard','auto',1)", id);
        return id;
    }

    private String knowledge(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','测试分科','测试章节','core','active','','',0,1)
                """, id, "PROGRESS-" + id, name);
        return id;
    }

    private BookFixture book(String name) {
        String id = UUID.randomUUID().toString(), root = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,?,TRUE,1,1)", id, name, name + "说明");
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'ROOT','总章','',0,1)", root, id);
        return new BookFixture(id, root);
    }

    private String chapter(String book, String parent, String code, String name, int order) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,parent_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,?,?,?,'',?,1)",
                id, book, parent, code, name, order);
        return id;
    }

    private void member(String book, String chapter, String point, int order) {
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",
                book, point, chapter, order);
        Integer covered = jdbc.queryForObject("""
                SELECT COUNT(*) FROM question_resource_knowledge qk
                  JOIN question_resource q ON q.id=qk.question_id
                 WHERE qk.knowledge_point_id=? AND qk.relation_role='core'
                   AND q.status='published' AND q.question_type='true_false'
                """, Integer.class, point);
        if (covered == null || covered == 0) {
            String question = question();
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                    question, point);
        }
    }

    private void select(String learner, String book) {
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
    }

    private String question() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto','进度题','true','解析',2,'published',1)
                """, id);
        return id;
    }

    private String attempt(String learner, String world, String practice, String question, String point,
                           String status, Instant answeredAt) {
        return attempt(learner, world, practice, question, point, status, answeredAt, "correct");
    }

    private String attempt(String learner, String world, String practice, String question, String point,
                           String status, Instant answeredAt, String assessment) {
        String attemptId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,grading_source,assessment,
                    target_knowledge_point_id,evidence_mode,question_difficulty,answered_at)
                VALUES (?,NULL,?,?,?,?, '{}','true',?,'auto',?,?,?,'normal',2,?)
                """, attemptId, learner, world, practice, question, status,
                "graded".equals(status) ? "automatic" : null,
                "graded".equals(status) ? assessment : null, point,
                answeredAt == null ? null : Timestamp.from(answeredAt));
        return attemptId;
    }

    /** 自评题先查看参考解析：status=revealed，不写 assessment，也不产生 Mastery / Evidence。 */
    private String reveal(String learner, String question, String point, Instant revealedAt) {
        String attemptId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,assessment,
                    answer_revealed_at,target_knowledge_point_id,evidence_mode,question_difficulty)
                VALUES (?,NULL,?,NULL,?,?,'{}','true','revealed','self_assessment',NULL,?,?,'normal',2)
                """, attemptId, learner, null, question, Timestamp.from(revealedAt), point);
        return attemptId;
    }

    private void saveMastery(String learner, String point, double mastery, double stability, Instant at) {
        String question = jdbc.queryForObject("""
                SELECT q.id FROM question_resource q
                JOIN question_resource_knowledge qk ON qk.question_id=q.id
                WHERE qk.knowledge_point_id=? AND qk.relation_role='core' AND q.parent_question_id IS NULL
                ORDER BY q.id LIMIT 1
                """, String.class, point);
        java.time.LocalDate today = NOW.atZone(LearnerQuestionMasteryStore.BUSINESS_ZONE).toLocalDate();
        jdbc.update("""
                INSERT INTO learner_question_mastery(learner_id,knowledge_point_id,question_id,score,
                    first_correct_at,last_correct_at,last_reward_date,last_decay_date,last_assessment,last_attempt_at,
                    decay_frozen,revision) VALUES (?,?,?,?,?,?,?,?,?,?,FALSE,1)
                """, learner, point, question, mastery, Timestamp.from(at), Timestamp.from(at),
                java.sql.Date.valueOf(today), java.sql.Date.valueOf(today), "correct", Timestamp.from(at));
        states.save(learner, point, new KnowledgeMasteryModel.State(mastery, stability, 3, 1, 1, 0,
                "correct", at, at, KnowledgeModelPolicy.MODEL_VERSION, 1));
    }

    private record BookFixture(String id, String root) {}
}

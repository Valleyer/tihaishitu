package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR7 合并前复核必修 2：`GET /learner/progress` 的对外响应兼容回归。
 *
 * <p>契约：{@code recent} 必须保留旧字段 {@code gradedAttempts7d}、
 * {@code distinctKnowledgePoints7d}、{@code activeStudyDays7d}、{@code daily}、
 * {@code knowledgePoints}，且这些字段是 <b>graded-only</b>；新 {@code activity} 负责完整的
 * 有效答题口径（含 reveal-only）。两者语义不同，但都不能丢事实。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:progress-api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerProgressApiCompatibilityIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void keepsLegacyRecentFieldsGradedOnlyWhileActivityCountsRevealOnly() throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"pr7-compat\",\"displayName\":\"兼容学习者\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='pr7-compat'", String.class);
        String point = knowledge();
        BookFixture book = book();
        member(book, point);
        select(learner, book.id());
        String question = question(point);

        // 固定落在明确业务日，避免依赖“当前是当天几点”，并截断到数据库微秒精度。
        var zone = PracticeBusinessDay.ZONE;
        var today = Instant.now().atZone(zone).toLocalDate();
        Instant gradedAt = today.minusDays(1).atTime(10, 0).atZone(zone).toInstant();
        Instant revealedAt = today.atTime(9, 0).atZone(zone).toInstant();
        // 七天窗口外：只进入累计，不进入任何七日字段。
        Instant outside = today.minusDays(9).atTime(10, 0).atZone(zone).toInstant();
        attempt(learner, question, point, "graded", gradedAt, "correct");
        String revealedAttempt = reveal(learner, question, point, revealedAt);
        attempt(learner, question, point, "graded", outside, "correct");

        mvc.perform(get("/api/v1/learner/progress").cookie(cookie))
                .andExpect(status().isOk())
                // 旧字段存在且类型不变。
                .andExpect(jsonPath("$.recent.gradedAttempts7d").value(1))
                .andExpect(jsonPath("$.recent.distinctKnowledgePoints7d").value(1))
                .andExpect(jsonPath("$.recent.activeStudyDays7d").value(1))
                .andExpect(jsonPath("$.recent.daily.length()").value(7))
                .andExpect(jsonPath("$.recent.daily[5].date").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.recent.daily[5].gradedAttempts").value(1))
                .andExpect(jsonPath("$.recent.daily[5].distinctKnowledgePoints").value(1))
                .andExpect(jsonPath("$.recent.daily[6].gradedAttempts").value(0))
                .andExpect(jsonPath("$.recent.knowledgePoints").isArray())
                // 新 activity：完整有效答题口径，含 reveal-only 与全历史累计。
                .andExpect(jsonPath("$.activity.metrics.totalEffectiveAttempts").value(3))
                .andExpect(jsonPath("$.activity.metrics.todayEffectiveAttempts").value(1))
                .andExpect(jsonPath("$.activity.metrics.activeStudyDays7d").value(2))
                .andExpect(jsonPath("$.activity.metrics.touchedKnowledgePoints").value(1))
                .andExpect(jsonPath("$.activity.outcomes.correct").value(2))
                .andExpect(jsonPath("$.activity.outcomes.revealedOnly").value(1))
                .andExpect(jsonPath("$.activity.daily.length()").value(7))
                // 新增的最近接触：来源是有效 Attempt，按首次有效行动时间，含仅查看答案。
                .andExpect(jsonPath("$.recentContacts.length()").value(1))
                .andExpect(jsonPath("$.recentContacts[0].knowledgePointId").value(point))
                .andExpect(jsonPath("$.recentContacts[0].revealedOnly").value(true))
                .andExpect(jsonPath("$.recentContacts[0].evidenceCount").value(0))
                .andExpect(jsonPath("$.recentContacts[0].lastEffectiveContactAt").exists())
                .andExpect(jsonPath("$.recentContacts[0].lastEvidenceAt").doesNotExist())
                .andExpect(jsonPath("$.recentContacts[0].bookName").value("兼容文集"));

        // 仅查看答案不得改写 Attempt 状态，也不得产生 Mastery / Evidence / 错题本。
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM study_attempt WHERE id=?", String.class, revealedAttempt)).isEqualTo("revealed");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=?", Integer.class, learner)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_wrong_question WHERE learner_id=?", Integer.class, learner)).isZero();
    }

    private String knowledge() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','测试分科','测试章节','core','active','','',0,1)
                """, id, "COMPAT-" + id, "兼容知识点");
        return id;
    }

    private record BookFixture(String id, String root) {}

    private BookFixture book() {
        String id = UUID.randomUUID().toString(), root = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,?,TRUE,?,1)",
                id, "兼容文集", "", 1);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'ROOT','总章','',0,1)",
                root, id);
        return new BookFixture(id, root);
    }

    private void member(BookFixture book, String point) {
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",
                book.id(), point, book.root());
    }

    private void select(String learner, String book) {
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
    }

    private String question(String point) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto','兼容题','true','解析',2,'published',1)
                """, id);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                id, point);
        return id;
    }

    private void attempt(String learner, String question, String point, String status, Instant answeredAt,
                         String assessment) {
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,grading_source,assessment,
                    target_knowledge_point_id,evidence_mode,question_difficulty,answered_at)
                VALUES (?,NULL,?,NULL,NULL,?,'{}','true',?,'auto',?,?,?,'normal',2,?)
                """, UUID.randomUUID().toString(), learner, question, status,
                "graded".equals(status) ? "automatic" : null,
                "graded".equals(status) ? assessment : null, point, Timestamp.from(answeredAt));
    }

    /** 自评题先查看参考解析：status=revealed，不写 assessment，也不产生 Mastery / Evidence。 */
    private String reveal(String learner, String question, String point, Instant revealedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,assessment,
                    answer_revealed_at,target_knowledge_point_id,evidence_mode,question_difficulty)
                VALUES (?,NULL,?,NULL,NULL,?,'{}','true','revealed','self_assessment',NULL,?,?,'normal',2)
                """, id, learner, question, Timestamp.from(revealedAt), point);
        return id;
    }
}

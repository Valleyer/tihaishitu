package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR7 合并前复核必修 4：`GET /learner/progress` 每个请求只读一次全历史有效 Attempt。
 *
 * <p>统计事实（activity）、旧 recent 兼容投影与最近接触必须来自同一份快照，否则既浪费一次全
 * 历史扫描，也可能在同一响应内读到不同时间点。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:progress-single-read;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerProgressSingleReadIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LearnerActivityStore activityStore;

    @Test
    void readsEffectiveAttemptsOnlyOncePerProgressRequest() throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"pr7-single-read\",\"displayName\":\"单次读取学习者\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='pr7-single-read'",
                String.class);
        String point = knowledge();
        BookFixture book = book();
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",
                book.id(), point, book.root());
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learner, book.id());
        String question = question(point);
        // 两条有效 Attempt + 一条 reveal-only，确保三个视图都有非空事实可投影。
        Instant today = LocalDate.now(PracticeBusinessDay.ZONE).atTime(9, 0).atZone(PracticeBusinessDay.ZONE).toInstant();
        attempt(learner, question, point, "graded", today, "correct");
        attempt(learner, question, point, "graded", today.plusSeconds(60), "wrong");
        reveal(learner, question, point, today.plusSeconds(120));

        Mockito.clearInvocations(activityStore);

        mvc.perform(get("/api/v1/learner/progress").cookie(cookie))
                .andExpect(status().isOk())
                // 三个视图都返回真实事实，证明它们来自同一份快照而不是空实现。
                .andExpect(jsonPath("$.activity.metrics.totalEffectiveAttempts").value(3))
                .andExpect(jsonPath("$.activity.outcomes.correct").value(1))
                .andExpect(jsonPath("$.activity.outcomes.wrong").value(1))
                .andExpect(jsonPath("$.activity.outcomes.revealedOnly").value(1))
                .andExpect(jsonPath("$.recentContacts.length()").value(1))
                .andExpect(jsonPath("$.recent.gradedAttempts7d").value(2))
                .andExpect(jsonPath("$.recent.daily.length()").value(7));

        // 一次请求只能触发一次全历史有效 Attempt 读取。
        Mockito.verify(activityStore, Mockito.times(1))
                .effectiveActions(anyString(), any());
        Mockito.verifyNoMoreInteractions(activityStore);
    }

    private String knowledge() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','测试分科','测试章节','core','active','','',0,1)
                """, id, "SINGLE-" + id, "单次读取知识点");
        return id;
    }

    private record BookFixture(String id, String root) {}

    private BookFixture book() {
        String id = UUID.randomUUID().toString(), root = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,?,TRUE,?,1)",
                id, "单次读取文集", "", 1);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'ROOT','总章','',0,1)",
                root, id);
        return new BookFixture(id, root);
    }

    private String question(String point) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto','单次读取题','true','解析',2,'published',1)
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

    private void reveal(String learner, String question, String point, Instant revealedAt) {
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,assessment,
                    answer_revealed_at,target_knowledge_point_id,evidence_mode,question_difficulty)
                VALUES (?,NULL,?,NULL,NULL,?,'{}','true','revealed','self_assessment',NULL,?,?,'normal',2)
                """, UUID.randomUUID().toString(), learner, question, Timestamp.from(revealedAt), point);
    }
}

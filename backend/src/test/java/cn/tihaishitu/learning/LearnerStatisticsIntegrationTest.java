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
 * 旧 `/learner/statistics` 兼容接口（PR7 进度统计 V3）。
 *
 * <p>长期契约：该接口的 {@code summary} 必须与 {@code /learner/progress.activity} 的数字
 * 完全一致，因为两者共用 {@link LearnerActivityStatsService}。{@code days} 入参只影响
 * {@code daily} 曲线长度，不再影响 summary 语义。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-statistics;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerStatisticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reportsUnifiedSummaryWithLegacyOriginFieldsAndRequestedDailyWindow() throws Exception {
        Cookie cookie=mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType("application/json")
                .content("{\"username\":\"stats-user\",\"displayName\":\"统计学习者\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        String learner=jdbc.queryForObject("SELECT id FROM learner_account WHERE username='stats-user'",String.class);
        String point=knowledge();
        String question=question(point);
        book(learner, point);
        String drill=practice(learner,point,"knowledge_drill"), wrong=practice(learner,point,"wrong_review");
        // 固定落在统计窗口内部：不使用 CURRENT_TIMESTAMP，避免测试数据贴在
        // `answered_at <= now` 的上边界上，导致数据库时间与 JVM 时间的微小差异随机翻红。
        Instant occurredAt = Instant.now().minusSeconds(60);
        attempt(learner,drill,null,question,point,"correct",occurredAt);
        attempt(learner,wrong,null,question,point,"partial",occurredAt);
        attempt(learner,null,"ancient-official",question,point,"wrong",occurredAt);
        // 仅查看答案也算一次有效答题，并被单独分类为 revealedOnly。
        reveal(learner,question,point,occurredAt.plusSeconds(1));

        mvc.perform(get("/api/v1/learner/statistics?days=30").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.gradedAttempts").value(4))
                .andExpect(jsonPath("$.summary.activeStudyDays").value(1))
                .andExpect(jsonPath("$.summary.distinctKnowledgePoints").value(1))
                .andExpect(jsonPath("$.summary.knowledgeDrillAttempts").value(1))
                .andExpect(jsonPath("$.summary.wrongReviewAttempts").value(1))
                .andExpect(jsonPath("$.summary.worldAttempts").value(1))
                .andExpect(jsonPath("$.summary.correct").value(1))
                .andExpect(jsonPath("$.summary.partial").value(1))
                .andExpect(jsonPath("$.summary.wrong").value(1))
                .andExpect(jsonPath("$.summary.revealedOnly").value(1))
                .andExpect(jsonPath("$.daily.length()").value(30));
    }

    @Test
    void stillRejectsUnsupportedDayWindows() throws Exception {
        Cookie cookie=mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType("application/json")
                .content("{\"username\":\"stats-days\",\"displayName\":\"统计窗口学习者\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);

        mvc.perform(get("/api/v1/learner/statistics?days=14").cookie(cookie))
                .andExpect(status().isBadRequest());
    }

    private String knowledge(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,'测试','节','章','core','active','','',0,1)",id,"STATS-"+id,"统计知识");return id;}
    private String question(String point){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试','custom','true_false','true_false','auto','题干','true','解析',1,'published',1)",id);jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",id,point);return id;}
    /** PR7：统计受当前 Selected Books 约束，因此 fixture 必须建立真实可学习范围。 */
    private void book(String learner,String point){
        String bank=UUID.randomUUID().toString(), chapter=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,?,TRUE,?,1)",bank,"统计文集","",1);        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'ROOT','总章','',0,1)",chapter,bank);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",bank,point,chapter);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",learner,bank);
    }
    private String practice(String learner,String point,String intent){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,status,revision) VALUES (?,?,?,?, 'active',1)",id,learner,intent,point);return id;}
    private void attempt(String learner,String practice,String world,String question,String point,String assessment,Instant answeredAt){jdbc.update("INSERT INTO study_attempt(id,learner_id,world_id,practice_session_id,question_id,question_snapshot_json,standard_answer_json,status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,question_difficulty,answered_at) VALUES (?,?,?,?,?,'{}','true','graded','auto','automatic',?,?,'normal',1,?)",UUID.randomUUID().toString(),learner,world,practice,question,assessment,point,Timestamp.from(answeredAt));}
    private void reveal(String learner,String question,String point,Instant revealedAt){jdbc.update("INSERT INTO study_attempt(id,learner_id,question_id,question_snapshot_json,standard_answer_json,status,grading_mode,assessment,answer_revealed_at,target_knowledge_point_id,evidence_mode,question_difficulty) VALUES (?,?,?,'{}','true','revealed','self_assessment',NULL,?,?,'normal',1)",UUID.randomUUID().toString(),learner,question,Timestamp.from(revealedAt),point);}
}

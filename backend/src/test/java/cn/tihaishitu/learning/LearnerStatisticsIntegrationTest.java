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

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-statistics;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerStatisticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void derivesRangeSummaryDailyOutcomesAndAttemptOrigins() throws Exception {
        Cookie cookie=mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType("application/json")
                .content("{\"username\":\"stats-user\",\"displayName\":\"统计学习者\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        String learner=jdbc.queryForObject("SELECT id FROM learner_account WHERE username='stats-user'",String.class);
        String point=knowledge(), question=question();
        String drill=practice(learner,point,"knowledge_drill"), wrong=practice(learner,point,"wrong_review");
        attempt(learner,drill,null,question,point,"correct");
        attempt(learner,wrong,null,question,point,"partial");
        attempt(learner,null,"ancient-official",question,point,"wrong");

        mvc.perform(get("/api/v1/learner/statistics?days=30").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.gradedAttempts").value(3))
                .andExpect(jsonPath("$.summary.activeStudyDays").value(1))
                .andExpect(jsonPath("$.summary.distinctKnowledgePoints").value(1))
                .andExpect(jsonPath("$.summary.knowledgeDrillAttempts").value(1))
                .andExpect(jsonPath("$.summary.wrongReviewAttempts").value(1))
                .andExpect(jsonPath("$.summary.worldAttempts").value(1))
                .andExpect(jsonPath("$.summary.correct").value(1))
                .andExpect(jsonPath("$.summary.partial").value(1))
                .andExpect(jsonPath("$.summary.wrong").value(1))
                .andExpect(jsonPath("$.daily.length()").value(30));
    }

    private String knowledge(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,'测试','节','章','core','active','','',0,1)",id,"STATS-"+id,"统计知识");return id;}
    private String question(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试','custom','true_false','true_false','auto','题干','true','解析',1,'published',1)",id);return id;}
    private String practice(String learner,String point,String intent){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,status,revision) VALUES (?,?,?,?, 'active',1)",id,learner,intent,point);return id;}
    private void attempt(String learner,String practice,String world,String question,String point,String assessment){jdbc.update("INSERT INTO study_attempt(id,learner_id,world_id,practice_session_id,question_id,question_snapshot_json,standard_answer_json,status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,question_difficulty,answered_at) VALUES (?,?,?,?,?,'{}','true','graded','auto','automatic',?,?,'normal',1,CURRENT_TIMESTAMP)",UUID.randomUUID().toString(),learner,world,practice,question,assessment,point);}
}

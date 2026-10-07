package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties="spring.datasource.url=jdbc:h2:mem:question-report;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class QuestionReportIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;

    @Test void learnerIdentityIsDerivedAndReviewerCanResolve() throws Exception {
        Cookie learner = account("report-owner"); String learnerId = id("report-owner");
        Cookie other = account("report-other");
        Cookie reviewer = account("report-reviewer", "REVIEWER");
        Cookie contributor = account("report-contributor", "CONTRIBUTOR");
        String question = question(); String attempt = attempt(learnerId, question);
        String body = "{\"attemptId\":\"%s\",\"reason\":\"analysis_error\",\"comment\":\"推导第二步疑似有误\"}".formatted(attempt);

        mvc.perform(post("/api/v1/learner/question-reports").with(csrf()).cookie(other)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/learner/question-reports").with(csrf()).cookie(learner)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("open"));
        mvc.perform(post("/api/v1/learner/question-reports").with(csrf()).cookie(learner)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT learner_id FROM question_report WHERE attempt_id=?", String.class, attempt))
                .isEqualTo(learnerId);

        mvc.perform(get("/api/v1/manage/question-reports?status=open&page=0&size=20").cookie(contributor))
                .andExpect(status().isForbidden());
        String reportId = jdbc.queryForObject("SELECT id FROM question_report WHERE attempt_id=?", String.class, attempt);
        mvc.perform(get("/api/v1/manage/question-reports?status=open&page=0&size=20").cookie(reviewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(patch("/api/v1/manage/question-reports/{id}/status", reportId).with(csrf()).cookie(reviewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"resolved\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("resolved"));
        mvc.perform(patch("/api/v1/manage/question-reports/{id}/status", reportId).with(csrf()).cookie(reviewer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"dismissed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("dismissed"));
    }

    @Test void validatesReasonAndCommentLength() throws Exception {
        Cookie learner = account("report-validation"); String attempt = attempt(id("report-validation"), question());
        mvc.perform(post("/api/v1/learner/question-reports").with(csrf()).cookie(learner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"%s\",\"reason\":\"bad\",\"comment\":\"\"}".formatted(attempt)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/learner/question-reports").with(csrf()).cookie(learner)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"%s\",\"reason\":\"other\",\"comment\":\"%s\"}"
                        .formatted(attempt, "字".repeat(1001))))
                .andExpect(status().isBadRequest());
    }

    private Cookie account(String username, String... roles) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"displayName\":\"%s\",\"password\":\"password-123\"}"
                        .formatted(username, username))).andExpect(status().isCreated()).andReturn().getResponse()
                .getCookie(LearnerAuthService.COOKIE);
        for (String role : roles) jdbc.update("INSERT INTO learner_account_role(learner_id,role_name) VALUES (?,?)", id(username), role);
        return cookie;
    }
    private String id(String username) { return jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class, username); }
    private String question() {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,source_name,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试','custom','反馈来源','true_false','true_false','auto','题干',NULL,'解析',2,'published',1)",id);
        return id;
    }
    private String attempt(String learner,String question) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO study_attempt(id,learner_id,world_id,question_id,question_snapshot_json,standard_answer_json,status,grading_mode) VALUES (?,?,'ancient-official',?,'{}','true','active','auto')",id,learner,question);
        return id;
    }
}

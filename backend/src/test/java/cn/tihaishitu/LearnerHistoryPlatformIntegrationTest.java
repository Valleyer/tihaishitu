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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:platform-history;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerHistoryPlatformIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void gradedSnapshotsAreRecoveredAcrossWorldsOnlyForTheirLearner() throws Exception {
        Cookie learnerA = register("history_a");
        Cookie learnerB = register("history_b");
        String learnerId = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username = 'history_a'", String.class);
        String attemptId = UUID.randomUUID().toString();
        String questionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,question_id,
                                          question_snapshot_json,standard_answer_json,status,grading_mode)
                VALUES (?,NULL,?,'other-world',?,'{\"id\":\"snapshot-question\",\"question\":\"跨世界题目\"}','true','graded','auto')
                """, attemptId, learnerId, questionId);

        String body = "{\"attemptIds\":[\"%s\"]}".formatted(attemptId);
        mvc.perform(post("/api/v1/learner/history/questions").with(csrf()).cookie(learnerA)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].attemptId").value(attemptId))
                .andExpect(jsonPath("$.questions[0].questionId").value(questionId))
                .andExpect(jsonPath("$.questions[0].question.question").value("跨世界题目"));
        mvc.perform(post("/api/v1/learner/history/questions").with(csrf()).cookie(learnerB)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions").isEmpty());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"测试\",\"password\":\"password-123\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

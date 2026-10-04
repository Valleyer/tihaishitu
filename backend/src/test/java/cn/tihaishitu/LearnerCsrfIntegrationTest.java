package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-csrf;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerCsrfIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void learnerAndWorldMutationsRequireTheCookieCsrfToken() throws Exception {
        String bookId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'测试文集','',TRUE,1,1)", bookId);

        MvcResult csrfResult = mvc.perform(get("/api/v1/learner/auth/csrf"))
                .andExpect(status().isOk()).andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        String rawToken = csrfCookie.getValue();

        MvcResult registration = mvc.perform(post("/api/v1/learner/auth/register")
                        .cookie(csrfCookie).header("X-XSRF-TOKEN", rawToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"csrf_user\",\"displayName\":\"令牌测试\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated()).andReturn();
        Cookie learner = registration.getResponse().getCookie(LearnerAuthService.COOKIE);
        assertThat(learner).isNotNull();

        String profile = mapper.writeValueAsString(java.util.Map.of(
                "pace", "normal", "difficulty", "standard", "focusMode", "auto",
                "expectedRevision", 1, "selectedBookIds", java.util.List.of(bookId),
                "focusedKnowledgePointIds", java.util.List.of()));
        mvc.perform(put("/api/v1/learner/study-profile").cookie(learner)
                        .contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").cookie(learner)
                        .contentType(MediaType.APPLICATION_JSON).content(world("无令牌")))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/learner/study-profile").cookie(learner, csrfCookie)
                        .header("X-XSRF-TOKEN", rawToken)
                        .contentType(MediaType.APPLICATION_JSON).content(profile))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").cookie(learner, csrfCookie)
                        .header("X-XSRF-TOKEN", rawToken)
                        .contentType(MediaType.APPLICATION_JSON).content(world("有令牌")))
                .andExpect(status().isCreated());
    }

    private static String world(String name) {
        return "{\"characterName\":\"%s\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}".formatted(name);
    }
}

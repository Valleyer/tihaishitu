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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerAuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test void opaqueSessionsAreHashedAndAccountsStayIsolated() throws Exception {
        Cookie first = register("learner_a", "甲");
        Cookie second = register("learner_b", "乙");

        mvc.perform(get("/api/v1/learner/me").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("learner_a"));
        mvc.perform(get("/api/v1/learner/me").cookie(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("learner_b"));
        mvc.perform(get("/api/v1/learner/me")).andExpect(status().isUnauthorized());

        assertThat(first.getValue()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_session WHERE token_hash = ?", Integer.class,
                LearnerAuthService.hash(first.getValue()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_session WHERE token_hash = ?", Integer.class,
                first.getValue())).isZero();
    }

    private Cookie register(String username, String displayName) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"%s\",\"password\":\"password-123\"}".formatted(username, displayName)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }
}

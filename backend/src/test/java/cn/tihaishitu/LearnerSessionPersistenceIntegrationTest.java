package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:session-persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerSessionPersistenceIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void loginCookieRestoresSameLearnerAcrossBootstrapAndDeepLinkRequests() throws Exception {
        mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"session-user\",\"displayName\":\"会话学习者\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated());
        var login = mvc.perform(post("/api/v1/learner/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"session-user\",\"password\":\"password-123\"}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("THS_LEARNER_SESSION=", "Path=/", "Max-Age=2592000", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Secure");
        Cookie cookie = login.getResponse().getCookie(LearnerAuthService.COOKIE);
        assertThat(cookie).isNotNull();
        String id = mvc.perform(get("/api/v1/bootstrap").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.learner.username").value("session-user"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/learner/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("session-user"));
        mvc.perform(get("/api/v1/learning/books").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bootstrap").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.learner.username").value("session-user"));
        assertThat(id).contains("session-user");
    }
}

package cn.tihaishitu;

import jakarta.servlet.http.Cookie;
import cn.tihaishitu.learner.LearnerAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:manage-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=chief-editor",
        "app.initial-admin.password=a-long-test-password",
        "app.initial-admin.display-name=总编"
})
class ManageAuthIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void manageRequiresSessionAndBootstrapAdminCanLoginAndLogout() throws Exception {
        mvc.perform(get("/api/v1/manage/auth/me"))
                .andExpect(status().isUnauthorized());

        var login = mvc.perform(post("/api/v1/manage/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"username":"chief-editor","password":"a-long-test-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@ == 'ADMIN')]").exists())
                .andReturn();

        Cookie session = login.getResponse().getCookie(LearnerAuthService.COOKIE);
        mvc.perform(get("/api/v1/manage/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("chief-editor"));
        mvc.perform(post("/api/v1/manage/auth/logout").cookie(session).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        mvc.perform(post("/api/v1/manage/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"username":"chief-editor","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}

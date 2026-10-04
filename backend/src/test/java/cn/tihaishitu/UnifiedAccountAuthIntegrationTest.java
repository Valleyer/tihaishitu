package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:unified-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=unified-admin",
        "app.initial-admin.password=initial-admin-password",
        "app.initial-admin.display-name=统一管理员"
})
class UnifiedAccountAuthIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;

    @Test void initialAdminIsLearnerAndUsesOneSessionForHubAndManage() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_account WHERE username='unified-admin'", Integer.class)).isEqualTo(1);
        String id = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='unified-admin'", String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_study_profile WHERE learner_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_account_role WHERE learner_id=?", Integer.class, id)).isEqualTo(3);
        Cookie cookie = login("unified-admin", "initial-admin-password");
        mvc.perform(get("/api/v1/bootstrap").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.learner.id").value(id));
        mvc.perform(get("/api/v1/manage/auth/me").cookie(cookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.roles").isArray());
    }

    @Test void ordinaryLearnerKeepsHubWhenManagementRolesAreRemovedAndUnifiedLogoutRevokesBoth() throws Exception {
        Cookie cookie = register("unified-learner");
        String id = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='unified-learner'", String.class);
        mvc.perform(get("/api/v1/manage/auth/me").cookie(cookie)).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO learner_account_role(learner_id,role_name) VALUES (?,'ADMIN')", id);
        mvc.perform(get("/api/v1/manage/auth/me").cookie(cookie)).andExpect(status().isOk());
        jdbc.update("DELETE FROM learner_account_role WHERE learner_id=?", id);
        mvc.perform(get("/api/v1/bootstrap").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/manage/auth/me").cookie(cookie)).andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/learner/auth/logout").with(csrf()).cookie(cookie))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/bootstrap").cookie(cookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/manage/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test void v11MigratesOldAdminsAndReusesAnExistingLearnerWithTheSameUsername() {
        String url = "jdbc:h2:mem:unified-v11-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "")
                .target(MigrationVersion.fromVersion("10")).load().migrate();
        JdbcTemplate old = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        String oldOnlyId = UUID.randomUUID().toString();
        String duplicateAdminId = UUID.randomUUID().toString();
        String existingLearnerId = UUID.randomUUID().toString();
        old.update("INSERT INTO question_bank(id,name,description,enabled,weight_value) VALUES (?,'迁移测试文集','',TRUE,100)", UUID.randomUUID().toString());
        old.update("INSERT INTO app_user(id,username,display_name,password_hash,status) VALUES (?,'old-only-admin','旧管理员','x','active')", oldOnlyId);
        old.update("INSERT INTO app_user_role(user_id,role_name) VALUES (?,'ADMIN')", oldOnlyId);
        old.update("INSERT INTO app_user(id,username,display_name,password_hash,status) VALUES (?,'shared-user','旧同名管理员','x','active')", duplicateAdminId);
        old.update("INSERT INTO app_user_role(user_id,role_name) VALUES (?,'REVIEWER')", duplicateAdminId);
        old.update("INSERT INTO learner_account(id,username,display_name,password_hash,status) VALUES (?,'shared-user','现有学习者','x','active')", existingLearnerId);

        Flyway.configure().dataSource(url, "sa", "").load().migrate();

        assertThat(old.queryForObject("SELECT id FROM learner_account WHERE username='old-only-admin'", String.class))
                .isEqualTo(oldOnlyId);
        assertThat(old.queryForObject("SELECT COUNT(*) FROM learner_study_profile WHERE learner_id=?", Integer.class, oldOnlyId)).isOne();
        assertThat(old.queryForObject("SELECT COUNT(*) FROM learner_selected_book WHERE learner_id=?", Integer.class, oldOnlyId)).isPositive();
        assertThat(old.queryForObject("SELECT COUNT(*) FROM learner_account_role WHERE learner_id=? AND role_name='ADMIN'", Integer.class, oldOnlyId)).isOne();
        assertThat(old.queryForObject("SELECT COUNT(*) FROM learner_account WHERE LOWER(username)='shared-user'", Integer.class)).isOne();
        assertThat(old.queryForObject("SELECT COUNT(*) FROM learner_account_role WHERE learner_id=? AND role_name='REVIEWER'", Integer.class, existingLearnerId)).isOne();
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"普通学习者\",\"password\":\"password-123\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
    private Cookie login(String username,String password) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username,password)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

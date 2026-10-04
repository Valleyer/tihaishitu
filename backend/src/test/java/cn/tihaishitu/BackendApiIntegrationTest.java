package cn.tihaishitu;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BackendApiIntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void bootstrapDeclaresServerCatalog() throws Exception {
        mvc.perform(get("/api/v1/bootstrap").cookie(register("bootstrap_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionCatalog.source").value("server"))
                .andExpect(jsonPath("$.questionCatalog.canEdit").value(false))
                .andExpect(jsonPath("$.learner.username").value("bootstrap_user"))
                .andExpect(jsonPath("$.worlds").isArray())
                .andExpect(jsonPath("$.bankManifest").isArray());
    }

    @Test
    void gameCanBeCreatedReadAndDeletedWithoutQuestionBanks() throws Exception {
        String response = mvc.perform(post("/api/v1/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"折叶",
                                  "gender":"男",
                                  "bankIds":[],
                                  "weights":{}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.player.name").value("折叶"))
                .andExpect(jsonPath("$.adventure.locationId").value("old-school"))
                .andExpect(jsonPath("$.attempt").isEmpty())
                .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(response).path("id").asText();
        java.util.UUID.fromString(id);
        mvc.perform(get("/api/v1/games/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(delete("/api/v1/games/{id}", id))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/games/{id}", id))
                .andExpect(status().isNotFound());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"测试\",\"password\":\"password-123\"}"))
                .andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

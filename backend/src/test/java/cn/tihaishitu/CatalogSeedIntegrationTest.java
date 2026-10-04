package cn.tihaishitu;

import cn.tihaishitu.catalog.CatalogService;
import cn.tihaishitu.catalog.LegacyCatalogMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-seed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.seed-enabled=true"
})
@AutoConfigureMockMvc
class CatalogSeedIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CatalogService catalog;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LegacyCatalogMigrator migrator;

    @Test
    void legacyBanksAreIdempotentlyProjectedIntoGlobalResources() {
        int legacyQuestions = jdbc.queryForObject("SELECT COUNT(*) FROM question_item", Integer.class);
        int projected = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_question_map", Integer.class);
        int bankItems = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_item", Integer.class);
        org.assertj.core.api.Assertions.assertThat(projected).isEqualTo(legacyQuestions);
        org.assertj.core.api.Assertions.assertThat(bankItems).isEqualTo(legacyQuestions);

        migrator.migrateAfterSeed();
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM legacy_question_map", Integer.class))
                .isEqualTo(projected);
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_item", Integer.class))
                .isEqualTo(bankItems);
    }

    @Test
    void builtInCatalogIsSeededWithUuidKeysAndAvailableByManifest() throws Exception {
        String body = mvc.perform(get("/api/v1/bootstrap").cookie(register("seed_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankManifest").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("bankManifest").path(0).path("id").asText();
        UUID.fromString(id);
        String etag = mvc.perform(get("/api/v1/question-banks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.questions").isNotEmpty())
                .andExpect(jsonPath("$.knowledgePoints").isNotEmpty())
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/api/v1/question-banks/{id}", id).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void activityAnswerUsesOnlyUuidAndCompactAnswerPayload() throws Exception {
        String created = mvc.perform(post("/api/v1/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"折叶\",\"gender\":\"男\",\"bankIds\":[],\"weights\":{}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String gameId = mapper.readTree(created).path("id").asText();
        String started = mvc.perform(post("/api/v1/games/{id}/activities", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attempt.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode game = mapper.readTree(started);
        String attemptId = game.path("attempt").path("id").asText();
        String questionId = game.path("attempt").path("question").path("id").asText();
        UUID.fromString(attemptId);
        UUID.fromString(questionId);
        JsonNode answer = catalog.findAll().stream().flatMap(bank -> bank.questions().stream())
                .filter(question -> question.id().equals(questionId)).findFirst().orElseThrow().answer();
        var body = mapper.createObjectNode();
        body.put("attemptId", attemptId); body.put("questionId", questionId); body.set("answer", answer);
        mvc.perform(post("/api/v1/games/{id}/answers", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt.result.correct").value(true))
                .andExpect(jsonPath("$.records[0].questionId").value(questionId))
                .andExpect(jsonPath("$.records[0].question").doesNotExist());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"测试\",\"password\":\"password-123\"}"))
                .andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

package cn.tihaishitu;

import cn.tihaishitu.catalog.CatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void builtInCatalogIsSeededWithUuidKeysAndAvailableByManifest() throws Exception {
        String body = mvc.perform(get("/api/v1/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankManifest").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("bankManifest").path(0).path("id").asText();
        UUID.fromString(id);
        mvc.perform(get("/api/v1/question-banks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.questions").isNotEmpty())
                .andExpect(jsonPath("$.knowledgePoints").isNotEmpty());
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
}

package cn.tihaishitu;

import cn.tihaishitu.catalog.CatalogStore;
import cn.tihaishitu.game.GameStore;
import cn.tihaishitu.game.QuestionAttemptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:self-assessment;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.seed-enabled=true"
})
@AutoConfigureMockMvc
class SelfAssessmentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired GameStore games;
    @Autowired QuestionAttemptStore attempts;
    @Autowired CatalogStore catalog;
    @Autowired JdbcTemplate jdbc;

    @Test
    void solutionDoesNotLeakBeforeRevealAndPartialIsIdempotent() throws Exception {
        String created = mvc.perform(post("/api/v1/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"自评测试","gender":"男","bankIds":[],"weights":{}}
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String gameId = mapper.readTree(created).path("id").asText();
        var bank = catalog.findAll().get(0);
        var point = bank.knowledgePoints().get(0);
        String questionId = UUID.randomUUID().toString();
        String attemptId = UUID.randomUUID().toString();

        ObjectNode game = games.findObject(gameId);
        game.with("config").withArray("bankIds").add(bank.id());
        ObjectNode definition = mapper.createObjectNode();
        definition.put("id", "self-assessment-test");
        definition.put("passScore", 60);
        definition.set("tiers", mapper.readTree("""
                [{"minScore":0,"label":"未过关","rewards":{}},
                 {"minScore":60,"label":"基础过关","rewards":{}}]
                """));
        ObjectNode run = mapper.createObjectNode();
        run.put("id", UUID.randomUUID().toString());
        run.set("definition", definition);
        run.set("knowledgePointIds", mapper.valueToTree(java.util.List.of(point.id())));
        run.put("knowledgePointIndex", 0); run.put("training", false);
        run.put("trainingAnswered", 0); run.put("answered", 0); run.put("correct", 0);
        run.set("seenQuestionIds", mapper.createArrayNode()); run.put("status", "active");
        game.with("adventure").set("run", run);

        ObjectNode visibleQuestion = mapper.createObjectNode();
        visibleQuestion.put("id", questionId); visibleQuestion.put("subject", "数学一");
        visibleQuestion.put("category", "解答题"); visibleQuestion.put("chapter", "积分");
        visibleQuestion.put("type", "self_assessment"); visibleQuestion.put("originalType", "solution");
        visibleQuestion.put("presentationType", "self_assessment");
        visibleQuestion.put("gradingMode", "self_assessment");
        visibleQuestion.put("question", "求 $\\int_0^1 x\\,dx$。 ");
        visibleQuestion.set("options", mapper.createObjectNode());
        visibleQuestion.set("knowledgePointIds", mapper.valueToTree(java.util.List.of(point.id())));
        visibleQuestion.set("knowledgePoints", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("id", point.id()).put("name", point.name()).put("description", "").put("explanation", "")));
        ObjectNode attempt = mapper.createObjectNode();
        attempt.put("id", attemptId); attempt.set("question", visibleQuestion);
        attempt.set("scene", mapper.createObjectNode().put("task", "解答自评"));
        attempt.putNull("result"); attempt.putNull("reveal"); attempt.put("review", false);
        game.set("attempt", attempt);
        games.save(game);

        ObjectNode snapshot = visibleQuestion.deepCopy();
        snapshot.put("explanation", "由 $\\int_0^1 x\\,dx=\\frac12$。");
        snapshot.set("aliases", mapper.createArrayNode());
        attempts.create(attemptId, gameId, questionId, snapshot, mapper.getNodeFactory().textNode("$\\frac12$"),
                "self_assessment");

        mvc.perform(get("/api/v1/games/{id}", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt.reveal").isEmpty())
                .andExpect(jsonPath("$.attempt.question.explanation").doesNotExist())
                .andExpect(jsonPath("$.attempt.question.answer").doesNotExist());

        mvc.perform(post("/api/v1/games/{id}/answers/self-assess", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\",\"questionId\":\"" + questionId
                                + "\",\"assessment\":\"partial\"}"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/games/{id}/answers/reveal", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"" + attemptId + "\",\"questionId\":\"" + questionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt.reveal.standard").value("$\\frac12$"))
                .andExpect(jsonPath("$.attempt.reveal.explanation").isNotEmpty());

        String assessment = "{\"attemptId\":\"" + attemptId + "\",\"questionId\":\"" + questionId
                + "\",\"assessment\":\"partial\"}";
        mvc.perform(post("/api/v1/games/{id}/answers/self-assess", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content(assessment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt.result.assessment").value("partial"))
                .andExpect(jsonPath("$.attempt.result.correct").value(false));
        mvc.perform(post("/api/v1/games/{id}/answers/self-assess", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content(assessment))
                .andExpect(status().isOk());

        Integer records = jdbc.queryForObject("SELECT COUNT(*) FROM answer_record WHERE attempt_id = ?",
                Integer.class, attemptId);
        assertThat(records).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT assessment FROM study_attempt WHERE id = ?", String.class, attemptId))
                .isEqualTo("partial");
    }
}

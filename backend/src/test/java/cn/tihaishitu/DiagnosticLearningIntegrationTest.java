package cn.tihaishitu;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import cn.tihaishitu.world.WorldRegistry;
import cn.tihaishitu.world.WorldStateStore;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:diagnostic-learning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DiagnosticLearningIntegrationTest extends DiagnosticWorldTestSupport {
    @Autowired WorldStateStore worldStates;

    @Test
    void dependencyFailureIsRemediatedBeforeTargetRecheckWithoutRestoringWorldScore() throws Exception {
        Scenario scenario = scenario(1, true);
        Cookie cookie = register("diagnostic-dependency-route");
        configureLearner("diagnostic-dependency-route", scenario);
        forceScenarioPlan(scenario);
        initialize(cookie);

        JsonNode game = begin(cookie, "read");
        String rootAttempt = currentAttempt(game);
        assertThat(currentQuestion(game)).isEqualTo(scenario.rootQuestion());
        game = answer(cookie, game, false);

        String diagnosis = jdbc.queryForObject(
                "SELECT id FROM learner_diagnosis_session WHERE root_attempt_id=?", String.class, rootAttempt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_record WHERE attempt_id=?", Integer.class, rootAttempt)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, rootAttempt)).isZero();
        assertThat(game.path("adventure").path("run").path("correct").asInt()).isZero();

        game = next(cookie, game);
        String probe = currentAttempt(game);
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, probe))
                .isEqualTo("dependency_probe");
        game = answer(cookie, game, false);
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("remediating_dependency");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, probe)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isZero();

        game = next(cookie, game);
        String remediation = currentAttempt(game);
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, remediation))
                .isEqualTo("dependency_remediation");
        game = answer(cookie, game, true);
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("rechecking_target");

        game = next(cookie, game);
        String recheck = currentAttempt(game);
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, recheck))
                .isEqualTo("target_recheck");
        game = answer(cookie, game, true);

        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("resolved");
        assertThat(jdbc.queryForObject("SELECT resolution FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("dependency_gap");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, rootAttempt)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.dependencies().get(0))).isEqualTo(2);
        JsonNode run = game.path("adventure").path("run");
        assertThat(run.path("knowledgePointIndex").asInt()).isEqualTo(1);
        assertThat(run.path("correct").asInt()).isZero();
        assertThat(run.path("answered").asInt()).isEqualTo(4);
        assertThat(run.path("diagnosticAnswered").asInt()).isEqualTo(2);
        assertThat(run.path("trainingAnswered").asInt()).isOne();
    }

    @Test
    void formalExamRetriesKeepOnlyBestAndCompletedTaskPermanentlyCloses() throws Exception {
        ExamScenario scenario = examScenario();
        Cookie cookie = register("positive-exam-retries");
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class,
                "positive-exam-retries");
        jdbc.update("UPDATE learner_study_profile SET focus_mode='manual' WHERE learner_id=?", learner);
        for (int index = 0; index < scenario.points().size(); index++)
            jdbc.update("INSERT INTO learner_focus_knowledge(learner_id,knowledge_point_id,sort_order) VALUES (?,?,?)",
                    learner, scenario.points().get(index), index);
        initialize(cookie);
        var state = worldStates.find(learner, WorldRegistry.ANCIENT_OFFICIAL);
        state.with("adventure").put("locationId", "exam-street");
        state.with("adventure").with("exams").with("county-exam").put("status", "registered");
        worldStates.save(learner, WorldRegistry.ANCIENT_OFFICIAL, state);

        JsonNode first = completeExam(cookie, begin(cookie, "county-exam-paper"), true);
        assertThat(first.path("adventure").path("run").path("score").asInt()).isEqualTo(90);
        JsonNode firstRecord = first.path("adventure").path("exams").path("county-exam");
        assertThat(firstRecord.path("status").asText()).isEqualTo("registered");
        assertThat(firstRecord.path("attempts").asInt()).isZero();
        assertThat(firstRecord.path("lastScore").asInt()).isZero();
        first = finish(cookie, first);

        JsonNode second = completeExam(cookie, begin(cookie, "county-exam-paper"), false);
        JsonNode secondRecord = second.path("adventure").path("exams").path("county-exam");
        assertThat(second.path("adventure").path("run").path("score").asInt()).isEqualTo(100);
        assertThat(secondRecord.path("status").asText()).isEqualTo("passed");
        assertThat(secondRecord.path("best").asInt()).isEqualTo(100);
        assertThat(secondRecord.path("attempts").asInt()).isZero();
        assertThat(second.path("adventure").path("clears").path("county-exam-paper").asInt()).isOne();
        assertThat(second.path("adventure").path("inventory").path("county-pass-note").asInt()).isOne();
        second = finish(cookie, second);

        mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"county-exam-paper\"}"))
                .andExpect(status().isConflict());
    }

    private JsonNode completeExam(Cookie cookie, JsonNode game, boolean missFirst) throws Exception {
        boolean missed = false;
        while ("active".equals(game.path("adventure").path("run").path("status").asText())) {
            boolean training = game.path("adventure").path("run").path("training").asBoolean();
            boolean answer = !missFirst || missed || training;
            if (!answer) missed = true;
            game = answer(cookie, game, answer);
            if ("active".equals(game.path("adventure").path("run").path("status").asText()))
                game = next(cookie, game);
        }
        return game;
    }
}

package cn.tihaishitu;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:diagnostic-target;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DiagnosticTargetAttributionIntegrationTest extends DiagnosticWorldTestSupport {

    @Test
    void allDependenciesPassBeforeRootFailureIsAttributedToTarget() throws Exception {
        Scenario scenario = scenario(2, true);
        Cookie cookie = register("diagnostic-target-route");
        configureLearner("diagnostic-target-route", scenario);
        forceScenarioPlan(scenario);
        initialize(cookie);

        JsonNode game = begin(cookie, "read");
        String rootAttempt = currentAttempt(game);
        game = answer(cookie, game, false);
        String diagnosis = jdbc.queryForObject(
                "SELECT id FROM learner_diagnosis_session WHERE root_attempt_id=?", String.class, rootAttempt);

        for (int index = 0; index < 2; index++) {
            game = next(cookie, game);
            String probe = currentAttempt(game);
            assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, probe))
                    .isEqualTo("dependency_probe");
            game = answer(cookie, game, true);
        }

        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("remediating_target");
        assertThat(jdbc.queryForObject("SELECT outcome FROM learner_knowledge_evidence WHERE attempt_id=?", String.class, rootAttempt))
                .isEqualTo("wrong");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isOne();
        for (String dependency : scenario.dependencies())
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, dependency)).isOne();

        game = next(cookie, game);
        String remediation = currentAttempt(game);
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, remediation))
                .isEqualTo("target_remediation");
        game = answer(cookie, game, true);

        assertThat(jdbc.queryForObject("SELECT resolution FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("target_gap");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isEqualTo(2);
        assertThat(game.path("adventure").path("run").path("knowledgePointIndex").asInt()).isOne();
        assertThat(game.path("adventure").path("run").path("correct").asInt()).isZero();
    }
}

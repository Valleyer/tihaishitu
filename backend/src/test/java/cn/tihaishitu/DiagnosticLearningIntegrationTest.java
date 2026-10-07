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

import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    /**
     * 覆盖保留下来的诊断能力：依赖探针失败 → 依赖补救 → 目标复核 → 归因 dependency_gap，
     * 根题的错误证据始终不落到目标知识点。
     *
     * <p>PR3 后普通正式训练不再自动进入诊断链，因此本用例直接通过
     * {@link cn.tihaishitu.learning.DiagnosticLearningService} 驱动状态机；
     * 只属于 World 运行态的断言（run 计数、world best score 等）已不再适用。</p>
     */
    @Test
    void dependencyFailureIsRemediatedBeforeTargetRecheckWithoutRestoringWorldScore() throws Exception {
        Scenario scenario = scenario(1, true);
        register("diagnostic-dependency-route");
        String learner = configureLearner("diagnostic-dependency-route", scenario);
        String dependency = scenario.dependencies().get(0);
        Set<String> allowed = Set.copyOf(scenario.dependencies());
        Instant at = Instant.now();

        String rootAttempt = createDiagnosisAttempt(learner, scenario.rootQuestion(),
                rootKnowledgePointIds(scenario), scenario.target(), "normal", null, null);
        var started = gradeDiagnosisAttempt(learner, rootAttempt, false, allowed, at);
        assertThat(started.diagnosisStarted()).isTrue();
        String diagnosis = started.diagnosisSessionId();
        assertThat(diagnosis).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT id FROM learner_diagnosis_session WHERE root_attempt_id=?", String.class, rootAttempt))
                .isEqualTo(diagnosis);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_record WHERE attempt_id=?", Integer.class, rootAttempt)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, rootAttempt)).isZero();

        // 依赖探针答错：依赖转 failed，会话转入 remediating_dependency。
        var probeDirective = nextDiagnosisDirective(learner, diagnosis);
        assertThat(probeDirective.role()).isEqualTo("dependency_probe");
        assertThat(probeDirective.targetKnowledgePointId()).isEqualTo(dependency);
        String probe = createDiagnosisAttempt(learner, formalQuestion(dependency), List.of(dependency), dependency,
                probeDirective.evidenceMode(), probeDirective.diagnosisSessionId(), probeDirective.role());
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, probe))
                .isEqualTo("dependency_probe");
        gradeDiagnosisAttempt(learner, probe, false, allowed, at.plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("remediating_dependency");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, probe)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isZero();

        // 依赖补救答对：依赖转 remediated，会话转入 rechecking_target。
        var remediationDirective = nextDiagnosisDirective(learner, diagnosis);
        assertThat(remediationDirective.role()).isEqualTo("dependency_remediation");
        assertThat(remediationDirective.targetKnowledgePointId()).isEqualTo(dependency);
        String remediation = createDiagnosisAttempt(learner, formalQuestion(dependency), List.of(dependency), dependency,
                remediationDirective.evidenceMode(), remediationDirective.diagnosisSessionId(), remediationDirective.role());
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, remediation))
                .isEqualTo("dependency_remediation");
        gradeDiagnosisAttempt(learner, remediation, true, allowed, at.plusSeconds(120));
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("rechecking_target");

        // 目标复核答对：归因 dependency_gap，根题错误被依赖缺口吸收。
        var recheckDirective = nextDiagnosisDirective(learner, diagnosis);
        assertThat(recheckDirective.role()).isEqualTo("target_recheck");
        assertThat(recheckDirective.targetKnowledgePointId()).isEqualTo(scenario.target());
        String recheck = createDiagnosisAttempt(learner, formalQuestion(scenario.target()), List.of(scenario.target()),
                scenario.target(), recheckDirective.evidenceMode(), recheckDirective.diagnosisSessionId(), recheckDirective.role());
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, recheck))
                .isEqualTo("target_recheck");
        var resolved = gradeDiagnosisAttempt(learner, recheck, true, allowed, at.plusSeconds(180));
        assertThat(resolved.targetCompleted()).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("resolved");
        assertThat(jdbc.queryForObject("SELECT resolution FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("dependency_gap");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, rootAttempt)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, dependency)).isEqualTo(2);
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

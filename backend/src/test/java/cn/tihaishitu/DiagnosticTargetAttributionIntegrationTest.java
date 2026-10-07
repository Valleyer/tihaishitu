package cn.tihaishitu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标知识点归因：全部依赖通过后根题错误才记到目标（target_gap）；
 * 依赖不可练时不做归因（inconclusive）。
 *
 * <p>PR3 后普通正式训练不再自动进入诊断链，这里直接通过
 * {@link cn.tihaishitu.learning.DiagnosticLearningService} 驱动保留的状态机。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:diagnostic-target;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DiagnosticTargetAttributionIntegrationTest extends DiagnosticWorldTestSupport {

    @Test
    void allDependenciesPassBeforeRootFailureIsAttributedToTarget() throws Exception {
        Scenario scenario = scenario(2, true);
        register("diagnostic-target-route");
        String learner = configureLearner("diagnostic-target-route", scenario);
        Set<String> allowed = Set.copyOf(scenario.dependencies());
        Instant at = Instant.now();

        String rootAttempt = createDiagnosisAttempt(learner, scenario.rootQuestion(),
                rootKnowledgePointIds(scenario), scenario.target(), "normal", null, null);
        var started = gradeDiagnosisAttempt(learner, rootAttempt, false, allowed, at);
        assertThat(started.diagnosisStarted()).isTrue();
        String diagnosis = started.diagnosisSessionId();

        Set<String> probed = new LinkedHashSet<>();
        while (probed.size() < scenario.dependencies().size()) {
            var directive = nextDiagnosisDirective(learner, diagnosis);
            assertThat(directive.role()).isEqualTo("dependency_probe");
            assertThat(probed.add(directive.targetKnowledgePointId())).isTrue();
            String probe = createDiagnosisAttempt(learner, formalQuestion(directive.targetKnowledgePointId()),
                    List.of(directive.targetKnowledgePointId()), directive.targetKnowledgePointId(),
                    directive.evidenceMode(), directive.diagnosisSessionId(), directive.role());
            assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, probe))
                    .isEqualTo("dependency_probe");
            gradeDiagnosisAttempt(learner, probe, true, allowed, at.plusSeconds(60L * probed.size()));
        }

        // 全部依赖通过后，根题错误才作为目标知识点的证据入账。
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("remediating_target");
        assertThat(jdbc.queryForObject("SELECT outcome FROM learner_knowledge_evidence WHERE attempt_id=?", String.class, rootAttempt))
                .isEqualTo("wrong");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isOne();
        for (String dependency : scenario.dependencies())
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, dependency)).isOne();

        // 目标补救答对：归因 target_gap，目标知识点累计两条证据（根题错误 + 补救正确）。
        var remediationDirective = nextDiagnosisDirective(learner, diagnosis);
        assertThat(remediationDirective.role()).isEqualTo("target_remediation");
        String remediation = createDiagnosisAttempt(learner, formalQuestion(scenario.target()),
                List.of(scenario.target()), remediationDirective.targetKnowledgePointId(),
                remediationDirective.evidenceMode(), remediationDirective.diagnosisSessionId(), remediationDirective.role());
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, remediation))
                .isEqualTo("target_remediation");
        gradeDiagnosisAttempt(learner, remediation, true, allowed, at.plusSeconds(600));

        assertThat(jdbc.queryForObject("SELECT resolution FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("target_gap");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isEqualTo(2);
    }

    /**
     * 依赖不可练的两种来源都让诊断跳过依赖探针、直接复核目标：
     * 会话创建时不在可练范围内（unavailable），以及探针发不出正式题后被标记不可用；
     * 目标复核答对也不能把根题错误归因到目标，只能归因为 inconclusive。
     */
    @Test
    void unavailableDependencySkipsProbeAndTargetRecheckResolvesInconclusive() throws Exception {
        Scenario scenario = scenario(2, true);
        register("diagnostic-unavailable-route");
        String learner = configureLearner("diagnostic-unavailable-route", scenario);
        String probeable = scenario.dependencies().get(0), unavailable = scenario.dependencies().get(1);
        // 只有第一个依赖在冻结的可练范围内，第二个创建会话时即为 unavailable。
        Set<String> allowed = Set.of(probeable);
        Instant at = Instant.now();

        String rootAttempt = createDiagnosisAttempt(learner, scenario.rootQuestion(),
                rootKnowledgePointIds(scenario), scenario.target(), "normal", null, null);
        var started = gradeDiagnosisAttempt(learner, rootAttempt, false, allowed, at);
        assertThat(started.diagnosisStarted()).isTrue();
        String diagnosis = started.diagnosisSessionId();
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?",
                String.class, diagnosis, unavailable)).isEqualTo("unavailable");
        assertThat(jdbc.queryForObject("SELECT has_unavailable_dependency FROM learner_diagnosis_session WHERE id=?", Boolean.class, diagnosis)).isTrue();

        // 可练依赖先走探针；探针发不出正式题时由调用方标记 unavailable。
        var probeDirective = nextDiagnosisDirective(learner, diagnosis);
        assertThat(probeDirective.role()).isEqualTo("dependency_probe");
        assertThat(probeDirective.targetKnowledgePointId()).isEqualTo(probeable);
        markProbeUnavailable(learner, diagnosis, probeable);
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?",
                String.class, diagnosis, probeable)).isEqualTo("unavailable");

        var recheckDirective = nextDiagnosisDirective(learner, diagnosis);
        assertThat(recheckDirective.role()).isEqualTo("target_recheck");
        assertThat(recheckDirective.targetKnowledgePointId()).isEqualTo(scenario.target());
        String recheck = createDiagnosisAttempt(learner, formalQuestion(scenario.target()), List.of(scenario.target()),
                scenario.target(), recheckDirective.evidenceMode(), recheckDirective.diagnosisSessionId(), recheckDirective.role());
        gradeDiagnosisAttempt(learner, recheck, true, allowed, at.plusSeconds(60));

        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("resolved");
        assertThat(jdbc.queryForObject("SELECT resolution FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis))
                .isEqualTo("inconclusive");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, rootAttempt)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, scenario.target())).isOne();
    }
}

package cn.tihaishitu;

import cn.tihaishitu.manage.KnowledgeManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识点归并下的诊断依赖收敛，以及未完成诊断的放弃：两者都是保留下来的诊断能力，
 * 由测试直接通过 {@link cn.tihaishitu.learning.DiagnosticLearningService} 驱动。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:diagnostic-merge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DiagnosticMergeIntegrationTest extends DiagnosticWorldTestSupport {
    @Autowired KnowledgeManagementService management;

    @Test
    void duplicateDependenciesMergeSafelyThenDiagnosisCanBeAbandonedWithoutRootEvidence() throws Exception {
        Scenario scenario = scenario(2, false);
        register("diagnostic-merge-route");
        String learner = configureLearner("diagnostic-merge-route", scenario);
        Set<String> allowed = Set.copyOf(scenario.dependencies());
        Instant at = Instant.now();

        String rootAttempt = createDiagnosisAttempt(learner, scenario.rootQuestion(),
                rootKnowledgePointIds(scenario), scenario.target(), "normal", null, null);
        var started = gradeDiagnosisAttempt(learner, rootAttempt, false, allowed, at);
        assertThat(started.diagnosisStarted()).isTrue();
        String diagnosis = started.diagnosisSessionId();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=?", Integer.class, diagnosis)).isEqualTo(2);

        String actor = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                actor, "diagnostic-actor-" + actor, "诊断管理员");
        String source = scenario.dependencies().get(0), target = scenario.dependencies().get(1);
        management.merge(source, target, 1, "诊断依赖归一", actor);

        // 重复依赖归并到 canonical 知识点，保留下来的依赖仍然可练（pending）。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=?", Integer.class, diagnosis)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?", Integer.class, diagnosis, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?", String.class, diagnosis, target)).isEqualTo("pending");

        // 归并后探针落在 canonical 知识点上，诊断继续推进而不是整段跳过。
        var probeDirective = nextDiagnosisDirective(learner, diagnosis);
        assertThat(probeDirective.role()).isEqualTo("dependency_probe");
        assertThat(probeDirective.targetKnowledgePointId()).isEqualTo(target);
        String probe = createDiagnosisAttempt(learner, formalQuestion(target), List.of(target),
                probeDirective.targetKnowledgePointId(), probeDirective.evidenceMode(),
                probeDirective.diagnosisSessionId(), probeDirective.role());
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class,
                probe)).isEqualTo("dependency_probe");
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM study_attempt WHERE id=?", String.class,
                probe)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?",
                String.class, diagnosis, target)).isEqualTo("pending");

        // 放弃诊断时不能把根错误强行归因到目标知识点。
        abandonDiagnosis(learner, diagnosis);
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis)).isEqualTo("abandoned");
        assertThat(jdbc.queryForObject("SELECT resolution FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis)).isEqualTo("abandoned");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, rootAttempt)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM global_knowledge_point WHERE id=?", String.class, source)).isEqualTo("deprecated");
    }
}

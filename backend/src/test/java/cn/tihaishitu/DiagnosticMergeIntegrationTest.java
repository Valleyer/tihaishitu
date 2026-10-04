package cn.tihaishitu;

import cn.tihaishitu.manage.KnowledgeManagementService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:diagnostic-merge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class DiagnosticMergeIntegrationTest extends DiagnosticWorldTestSupport {
    @Autowired KnowledgeManagementService management;

    @Test
    void duplicateDependenciesMergeSafelyThenUnavailableRouteCanBeAbandonedWithoutRootEvidence() throws Exception {
        Scenario scenario = scenario(2, false);
        Cookie cookie = register("diagnostic-merge-route");
        configureLearner("diagnostic-merge-route", scenario);
        initialize(cookie);

        JsonNode game = begin(cookie, "read");
        String rootAttempt = currentAttempt(game);
        game = answer(cookie, game, false);
        String diagnosis = jdbc.queryForObject(
                "SELECT id FROM learner_diagnosis_session WHERE root_attempt_id=?", String.class, rootAttempt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=?", Integer.class, diagnosis)).isEqualTo(2);

        String actor = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                actor, "diagnostic-actor-" + actor, "诊断管理员");
        String source = scenario.dependencies().get(0), target = scenario.dependencies().get(1);
        management.merge(source, target, 1, "诊断依赖归一", actor);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=?", Integer.class, diagnosis)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?", Integer.class, diagnosis, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?", String.class, diagnosis, target)).isEqualTo("pending");

        game = next(cookie, game);
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_dependency WHERE diagnosis_id=? AND knowledge_point_id=?", String.class, diagnosis, target)).isEqualTo("unavailable");
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis)).isEqualTo("rechecking_target");
        assertThat(jdbc.queryForObject("SELECT diagnosis_role FROM study_attempt WHERE id=?", String.class, currentAttempt(game))).isEqualTo("target_recheck");

        abandon(cookie, game);
        assertThat(jdbc.queryForObject("SELECT status FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis)).isEqualTo("abandoned");
        assertThat(jdbc.queryForObject("SELECT resolution FROM learner_diagnosis_session WHERE id=?", String.class, diagnosis)).isEqualTo("abandoned");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, rootAttempt)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM global_knowledge_point WHERE id=?", String.class, source)).isEqualTo("deprecated");
    }
}

package cn.tihaishitu;

import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learning.LearnerKnowledgeStateService;
import cn.tihaishitu.manage.KnowledgeManagementService;
import cn.tihaishitu.world.WorldActionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-knowledge-merge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerKnowledgeMergeIntegrationTest {
    @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper; @Autowired QuestionAttemptStore attempts;
    @Autowired LearnerKnowledgeStateService states; @Autowired KnowledgeManagementService management;

    @Test
    void crossWorldEvidenceSharesOneStateAndKnowledgeMergeCanonicalizesAndReplays() {
        String learner = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(), target = UUID.randomUUID().toString();
        String actor = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,'merge-learner','合并学习者','x','active',1)", learner);
        jdbc.update("INSERT INTO app_user(id,username,display_name,password_hash,status,revision) VALUES (?,'merge-actor','管理员','x','active',1)", actor);
        insertKnowledge(source, "MERGE-STATE-SOURCE"); insertKnowledge(target, "MERGE-STATE-TARGET");
        jdbc.update("INSERT INTO learner_focus_knowledge(learner_id,knowledge_point_id,sort_order) VALUES (?,?,2)", learner, source);
        jdbc.update("INSERT INTO learner_focus_knowledge(learner_id,knowledge_point_id,sort_order) VALUES (?,?,5)", learner, target);

        String sourceAttempt = evidence(learner, "ancient-official", source, "normal", "wrong", Instant.parse("2026-01-01T00:00:00Z"));
        String targetAttempt = evidence(learner, "other-world", target, "normal", "correct", Instant.parse("2026-01-02T00:00:00Z"));
        evidence(learner, "ancient-official", target, "training", "correct", Instant.parse("2026-01-03T00:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE learner_id=?", Integer.class, learner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT evidence_count FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT world_id) FROM learner_knowledge_evidence WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=?", Integer.class, learner)).isEqualTo(3);

        management.merge(source, target, 1, "同一概念统一", actor);

        assertThat(jdbc.queryForObject("SELECT status FROM global_knowledge_point WHERE id=?", String.class, source)).isEqualTo("deprecated");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT evidence_count FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE knowledge_point_id=?", Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, target)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sort_order FROM learner_focus_knowledge WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_focus_knowledge WHERE knowledge_point_id=?", Integer.class, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM study_attempt WHERE id=?", String.class, sourceAttempt)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM study_attempt WHERE id=?", String.class, targetAttempt)).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT world_id) FROM learner_knowledge_evidence WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(2);
    }

    private String evidence(String learner, String world, String point, String mode, String outcome, Instant at) {
        String attemptId = UUID.randomUUID().toString();
        WorldActionContext.run(learner, world, () -> {
            var question = mapper.createObjectNode().put("id", UUID.randomUUID().toString()).put("gradingMode", "auto");
            attempts.create(attemptId, world, question.path("id").asText(), question,
                    mapper.getNodeFactory().booleanNode(true), "auto", point, mode, 3);
            var snapshot = attempts.find(attemptId, world);
            boolean correct = "correct".equals(outcome);
            attempts.recordAnswer(snapshot, mapper.getNodeFactory().booleanNode(correct), correct, at);
            states.apply(snapshot, outcome, "automatic", at);
            return null;
        });
        return attemptId;
    }
    private void insertKnowledge(String id, String code) { jdbc.update("""
            INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision)
            VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
            """, id, code, code); }
}

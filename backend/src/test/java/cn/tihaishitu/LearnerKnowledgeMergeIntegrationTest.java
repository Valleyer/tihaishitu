package cn.tihaishitu;

import cn.tihaishitu.game.AnswerRequest;
import cn.tihaishitu.game.GameActionService;
import cn.tihaishitu.game.GameFactory;
import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learning.LearnerKnowledgeStateService;
import cn.tihaishitu.manage.KnowledgeManagementService;
import cn.tihaishitu.world.WorldActionContext;
import cn.tihaishitu.world.WorldStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    @Autowired GameActionService actions; @Autowired GameFactory factory; @Autowired WorldStateStore worldStates;

    @Test
    void crossWorldEvidenceSharesOneStateAndKnowledgeMergeCanonicalizesAndReplays() {
        String learner = UUID.randomUUID().toString(), source = UUID.randomUUID().toString(), target = UUID.randomUUID().toString();
        String actor = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,'merge-learner','合并学习者','x','active',1)", learner);
        jdbc.update("INSERT INTO learner_study_profile(learner_id,pace,difficulty,focus_mode,revision) VALUES (?,'normal','standard','manual',1)", learner);
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,'merge-actor','管理员','x','active',1)", actor);
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

    @Test
    void activeAttemptUsesCanonicalTargetWhenGradedAfterKnowledgeMerge() {
        String learner = UUID.randomUUID().toString(), source = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
        String world = "ancient-official", attemptId = UUID.randomUUID().toString();
        String questionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,'并发学习者','x','active',1)",
                learner, "active-merge-" + learner);
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,'并发管理员','x','active',1)",
                actor, "active-actor-" + actor);
        insertKnowledge(source, "ACTIVE-MERGE-SOURCE");
        insertKnowledge(target, "ACTIVE-MERGE-TARGET");

        ObjectNode question = mapper.createObjectNode();
        question.put("id", questionId);
        question.put("gradingMode", "auto");
        question.put("explanation", "解析");
        question.set("aliases", mapper.createArrayNode());
        WorldActionContext.run(learner, world, () -> {
            attempts.create(attemptId, world, questionId, question, mapper.getNodeFactory().booleanNode(true),
                    "auto", source, "normal", 3);
            return null;
        });
        ObjectNode state = factory.createAncientOfficialState("并发学子", "男", "寒门读书人");
        ObjectNode current = mapper.createObjectNode();
        current.put("id", attemptId);
        current.set("question", question.deepCopy());
        current.putNull("result");
        current.put("review", false);
        state.set("attempt", current);
        ObjectNode run = mapper.createObjectNode();
        run.put("id", UUID.randomUUID().toString());
        run.set("definition", mapper.createObjectNode().put("id", "concurrency-test").put("passScore", 60));
        run.put("answered", 0);
        run.put("correct", 0);
        run.set("knowledgePointIds", mapper.valueToTree(java.util.List.of(source)));
        run.put("knowledgePointIndex", 0);
        run.put("training", false);
        run.put("trainingAnswered", 0);
        run.set("seenQuestionIds", mapper.createArrayNode());
        run.put("status", "active");
        state.with("adventure").set("run", run);
        worldStates.insert(learner, world, state);

        management.merge(source, target, 1, "合并 active attempt 目标", actor);
        WorldActionContext.run(learner, world, () -> actions.answer(world,
                new AnswerRequest(attemptId, questionId, mapper.getNodeFactory().booleanNode(true))));

        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM study_attempt WHERE id=?", String.class, attemptId))
                .isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, source)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?", Integer.class, learner, target)).isEqualTo(1);
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

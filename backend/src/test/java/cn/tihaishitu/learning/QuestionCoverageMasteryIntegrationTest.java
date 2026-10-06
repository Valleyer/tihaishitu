package cn.tihaishitu.learning;

import cn.tihaishitu.game.QuestionAttemptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:coverage-mastery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class QuestionCoverageMasteryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired LearnerKnowledgeStateService states;

    @Test
    void eachQuestionOwnsOneCoverageSlotAndDueCorrectOnlyRefreshesStability() throws Exception {
        String learner = UUID.randomUUID().toString(), point = UUID.randomUUID().toString();
        String q1 = UUID.randomUUID().toString(), q2 = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                learner, "coverage-" + learner, "覆盖度");
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, point, "COVERAGE-" + point, "覆盖度");
        question(q1, point, 2); question(q2, point, 4);

        apply(learner, point, q1, "correct", Instant.parse("2026-01-01T00:00:00Z"));
        assertState(learner, point, 50, 1);
        double firstStability = stability(learner, point);
        int firstEvidence = evidence(learner, point);

        apply(learner, point, q1, "correct", Instant.parse("2026-01-01T00:01:00Z"));
        assertState(learner, point, 50, 1);
        assertThat(stability(learner, point)).isEqualTo(firstStability);
        assertThat(evidence(learner, point)).isEqualTo(firstEvidence);

        apply(learner, point, q1, "wrong", Instant.parse("2026-01-01T00:02:00Z"));
        assertState(learner, point, 0, 1);
        int afterWrong = evidence(learner, point);
        apply(learner, point, q1, "wrong", Instant.parse("2026-01-01T00:03:00Z"));
        assertState(learner, point, 0, 1);
        assertThat(evidence(learner, point)).isEqualTo(afterWrong);

        apply(learner, point, q1, "correct", Instant.parse("2026-01-01T00:04:00Z"));
        assertState(learner, point, 50, 1);
        apply(learner, point, q2, "correct", Instant.parse("2026-01-01T00:05:00Z"));
        assertState(learner, point, 100, 2);

        jdbc.update("UPDATE learner_knowledge_state SET last_evidence_at=?,stability_days=.5 WHERE learner_id=? AND knowledge_point_id=?",
                Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)), learner, point);
        double beforeReview = stability(learner, point);
        apply(learner, point, q1, "correct", Instant.now());
        assertState(learner, point, 100, 2);
        assertThat(stability(learner, point)).isGreaterThan(beforeReview);
        assertThat(jdbc.queryForObject("SELECT model_version FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?",
                String.class, learner, point)).isEqualTo("v2-coverage");
    }

    @Test
    void legacyStateRebuildIsIdempotentAndKeepsSpacingState() throws Exception {
        String learner = UUID.randomUUID().toString(), point = UUID.randomUUID().toString();
        String q1 = UUID.randomUUID().toString(), q2 = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                learner, "legacy-" + learner, "旧状态");
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, point, "LEGACY-" + point, "旧状态");
        question(q1, point, 2); question(q2, point, 3);
        insertGraded(learner, point, q1, "correct", Instant.parse("2026-01-01T00:00:00Z"));
        insertGraded(learner, point, q2, "wrong", Instant.parse("2026-01-02T00:00:00Z"));
        jdbc.update("""
                INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,
                    target_difficulty,evidence_count,correct_streak,wrong_streak,last_outcome,last_evidence_at,
                    last_correct_at,model_version,revision)
                VALUES (?,?,90,7,3,5,1,0,'correct',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v1',9)
                """, learner, point);

        states.rebuildLegacyStates();
        assertState(learner, point, 50, 2);
        assertThat(stability(learner, point)).isEqualTo(7);
        long revision = jdbc.queryForObject("SELECT revision FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?",
                Long.class, learner, point);
        states.rebuildLegacyStates();
        assertThat(jdbc.queryForObject("SELECT revision FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?",
                Long.class, learner, point)).isEqualTo(revision);
    }

    private void apply(String learner, String point, String question, String outcome, Instant at) throws Exception {
        String attempt = insertGraded(learner, point, question, outcome, at);
        var snapshot = new QuestionAttemptStore.Snapshot(attempt, null, learner, null, null, question,
                mapper.readTree("{}"), mapper.readTree("true"), "graded", "auto", "automatic", outcome,
                point, "normal", 2, at, null, null);
        states.apply(snapshot, outcome, "automatic", at);
    }

    private String insertGraded(String learner, String point, String question, String outcome, Instant at) {
        String attempt = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,learner_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,
                    question_difficulty,answered_at)
                VALUES (?,?,?,'{}','true','graded','auto','automatic',?,?,'normal',2,?)
                """, attempt, learner, question, outcome, point, Timestamp.from(at));
        return attempt;
    }

    private void question(String id, String point, int difficulty) {
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','题','true','解析',?,'published',1)
                """, id, difficulty);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, point);
    }

    private void assertState(String learner, String point, double score, int count) {
        assertThat(jdbc.queryForObject("SELECT mastery_score FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?",
                Double.class, learner, point)).isEqualTo(score);
        assertThat(jdbc.queryForObject("SELECT evidence_count FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?",
                Integer.class, learner, point)).isEqualTo(count);
    }
    private double stability(String learner, String point) {
        return jdbc.queryForObject("SELECT stability_days FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?",
                Double.class, learner, point);
    }
    private int evidence(String learner, String point) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=? AND knowledge_point_id=?",
                Integer.class, learner, point);
    }
}

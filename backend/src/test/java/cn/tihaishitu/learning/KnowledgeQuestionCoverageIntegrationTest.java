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

/**
 * 知识点正式题覆盖口径必须唯一：core + auxiliary 都计入同一个分母。
 *
 * <p>回归用例：3 道相关正式真题（core / core / auxiliary），只做了 1 道且第一次正确时，
 * Knowledge Mastery 必须是 {@code 30 / 3 = 10.0%}，而不是只统计 core 得到的 15%。</p>
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:knowledge-question-coverage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class KnowledgeQuestionCoverageIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired LearnerKnowledgeStateService states;
    @Autowired LearnerQuestionMasteryStore questionMastery;

    @Test
    void auxiliaryQuestionsCountTowardsTheSameMasteryDenominator() throws Exception {
        String learner = learner("coverage-denominator");
        String point = point("COVERAGE-DENOMINATOR");
        String q1 = UUID.randomUUID().toString(), q2 = UUID.randomUUID().toString(), q3 = UUID.randomUUID().toString();
        question(q1, point, "core");
        question(q2, point, "core");
        question(q3, point, "auxiliary");
        Instant day1 = Instant.parse("2026-01-01T08:00:00Z");

        // 第一业务日第一次答对 Q1：30 / 3 = 10.0%
        assertThat(apply(learner, point, q1, "correct", day1).masteryScore()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT mastery_score FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=?",
                Double.class, learner, point)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE learner_id=? AND knowledge_point_id=? AND model_version='v3-question-reinforcement'",
                Integer.class, learner, point)).isEqualTo(1);

        // 同一业务日再答对一次：仍然是 10.0%
        assertThat(apply(learner, point, q1, "correct", day1.plus(1, ChronoUnit.HOURS)).masteryScore())
                .isEqualTo(10);

        // 第二业务日首答正确：30 + 7 = 37，37 / 3 = 12.3%
        assertThat(apply(learner, point, q1, "correct", day1.plus(1, ChronoUnit.DAYS)).masteryScore())
                .isEqualTo(12.3);

        // 只答 Q3（auxiliary）也能为同一个知识点加分，说明它确实属于该知识点的覆盖范围。
        assertThat(apply(learner, point, q3, "correct", day1.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS))
                .masteryScore()).isEqualTo(22.3);
    }

    @Test
    void denominatorMatchesTheKnowledgePointQuestionList() throws Exception {
        String learner = learner("coverage-list");
        String point = point("COVERAGE-LIST");
        String core = UUID.randomUUID().toString();
        String auxiliary = UUID.randomUUID().toString();
        question(core, point, "core");
        question(auxiliary, point, "auxiliary");

        Integer coverage = jdbc.queryForObject(
                KnowledgeQuestionCoveragePolicy.countSql("?"), Integer.class, point);
        Integer listed = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT q.id) FROM question_resource_knowledge qk
                  JOIN question_resource q ON q.id = qk.question_id
                 WHERE qk.knowledge_point_id = ? AND q.status = 'published'
                   AND q.parent_question_id IS NULL
                   AND q.question_type IN ('single_choice','multiple_choice','true_false','solution')
                """, Integer.class, point);
        assertThat(coverage).isEqualTo(2).isEqualTo(listed);

        // 掌握度投影的分母与上述口径一致：只做对一道 core 题得到 30 / 2 = 15.0%。
        assertThat(apply(learner, point, core, "correct", Instant.parse("2026-01-01T08:00:00Z"))
                .masteryScore()).isEqualTo(15);
    }

    private LearnerQuestionMasteryStore.Projection apply(String learner, String point, String question,
                                                          String outcome, Instant at) throws Exception {
        String attempt = insertGraded(learner, point, question, outcome, at);
        var snapshot = new QuestionAttemptStore.Snapshot(attempt, null, learner, null, null, question,
                mapper.readTree("{}"), mapper.readTree("true"), "graded", "auto", "automatic", outcome,
                point, "normal", 2, at, null, null);
        states.apply(snapshot, outcome, "automatic", at);
        return questionMastery.projection(learner, point, at);
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

    private void question(String id, String point, String role) {
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','题','true','解析',2,'published',1)
                """, id);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,?,0)",
                id, point, role);
    }

    private String learner(String prefix) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                id, prefix + "-" + id, prefix);
        return id;
    }

    private String point(String prefix) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, id, prefix + "-" + id, prefix);
        return id;
    }
}

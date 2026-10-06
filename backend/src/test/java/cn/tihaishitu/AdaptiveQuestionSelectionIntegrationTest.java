package cn.tihaishitu;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.learning.AdaptiveSchedulingPolicy;
import cn.tihaishitu.learning.KnowledgeMasteryModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:adaptive-question-selection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AdaptiveQuestionSelectionIntegrationTest {
    @Autowired KnowledgeQuestionPoolService pool;
    @Autowired JdbcTemplate jdbc;

    @Test
    void masteryCapProfilePreferenceSoftDifficultyTrainingAndSeenCompose() {
        String learner = learner();
        String point = knowledge();
        Map<Integer, String> questions = new LinkedHashMap<>();
        for (int difficulty = 1; difficulty <= 5; difficulty++)
            questions.put(difficulty, question(point, difficulty));
        Instant now = Instant.parse("2026-10-04T00:00:00Z");
        KnowledgeMasteryModel.State forgotten = state(30, 5, now);
        int standard = AdaptiveSchedulingPolicy.preferredDifficulty(forgotten, 30, "standard");
        int gentle = AdaptiveSchedulingPolicy.preferredDifficulty(forgotten, 30, "gentle");

        assertThat(standard).isEqualTo(2);
        assertThat(gentle).isEqualTo(1);
        assertThat(select(learner, point, Set.of(), standard, false).id()).isEqualTo(questions.get(2));
        assertThat(select(learner, point, Set.of(), gentle, false).id()).isEqualTo(questions.get(1));

        KnowledgeMasteryModel.State restored = state(90, 4, now);
        int restoredPreferred = AdaptiveSchedulingPolicy.preferredDifficulty(restored, 90, "standard");
        assertThat(restoredPreferred).isEqualTo(4);
        assertThat(select(learner, point, Set.of(), restoredPreferred, false).id()).isEqualTo(questions.get(4));

        Set<String> withoutExact = Set.of(questions.get(1), questions.get(3), questions.get(5));
        assertThat(select(learner, point, withoutExact, 3, false).id()).isEqualTo(questions.get(2));
        assertThat(select(learner, point, Set.of(), 4, true).id()).isEqualTo(questions.get(2));
        assertThat(select(learner, point, Set.of(questions.get(1), questions.get(2)), 4, true).id())
                .isEqualTo(questions.get(3));
    }

    private cn.tihaishitu.catalog.QuestionDto select(String learner, String point, Set<String> seen,
                                                       int preferred, boolean training) {
        return pool.selectQuestionForLearner(learner, new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(
                point, Set.of(point), Set.of(), seen, preferred, training
                ? KnowledgeQuestionPoolService.Mode.TRAINING
                : KnowledgeQuestionPoolService.Mode.NORMAL));
    }

    private String learner() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                id, "adaptive-" + id, "难度学习者");
        return id;
    }

    private String knowledge() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','分部','章节','core','active','','',0,1)
                """, id, "DIFF-" + id, "难度目标");
        return id;
    }

    private String question(String point, int difficulty) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',?,'published',1)
                """, id, "D" + difficulty, difficulty);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, point);
        return id;
    }

    private static KnowledgeMasteryModel.State state(double mastery, int targetDifficulty, Instant at) {
        return new KnowledgeMasteryModel.State(mastery, 365, targetDifficulty, 1, 0, 0,
                "correct", at, at, "v1", 1);
    }
}

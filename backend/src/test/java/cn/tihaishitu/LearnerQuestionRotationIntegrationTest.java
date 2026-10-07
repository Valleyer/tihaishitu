package cn.tihaishitu;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.game.LearnerQuestionExposureStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最新规则：正式题抽取不再按 Exposure 跨 run 软排序，也不再使用 difficulty / preferred difficulty 参与排名。
 * 唯一保留的跨 run 事实是 {@code study_attempt} 作为 Exposure 记录本身；
 * 普通 Formal Question 只在当前 Session 内用 seenQuestionIds 防止立刻重复，新 Session 重新进入随机池。
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:question-rotation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerQuestionRotationIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Autowired KnowledgeQuestionPoolService pool;
    @Autowired LearnerQuestionExposureStore exposures;
    @Autowired JdbcTemplate jdbc;

    @Test
    void exposureStillAggregatesAcrossWorldsAndStatuses() {
        String learner = learner();
        String point = knowledge();
        String seen = question(point, 2), freshA = question(point, 2), freshB = question(point, 2);
        expose(learner, "ancient-official", seen, "active", NOW.minus(3, ChronoUnit.DAYS));
        expose(learner, "future-world", seen, "revealed", NOW.minus(2, ChronoUnit.DAYS));
        expose(learner, "ancient-official", seen, "graded", NOW.minus(1, ChronoUnit.DAYS));

        assertThat(exposures.findForQuestions(learner, Set.of(seen)).get(seen))
                .isEqualTo(new LearnerQuestionExposureStore.Exposure(
                        seen, 3, NOW.minus(1, ChronoUnit.DAYS)));

        // 历史 Exposure 不再影响正式题抽取：新 Session 中三题都在随机池内。
        Set<String> drawn = new java.util.HashSet<>();
        for (int index = 0; index < 200; index++)
            drawn.add(select(learner, point, Set.of(), 2, KnowledgeQuestionPoolService.Mode.NORMAL).id());
        assertThat(drawn).containsExactlyInAnyOrder(seen, freshA, freshB);
    }

    @Test
    void sessionSeenIsAHardExclusionButANewSessionReopensThePool() {
        String learner = learner();
        String point = knowledge();
        String first = question(point, 3);
        String second = question(point, 3);
        expose(learner, "ancient-official", first, NOW.minus(10, ChronoUnit.DAYS));

        // Session 内 seen 是硬排除：只剩第二题时必须抽到它，而不是回退到已见题。
        assertThat(select(learner, point, Set.of(first), 3, KnowledgeQuestionPoolService.Mode.NORMAL).id())
                .isEqualTo(second);

        // 新 Session 重新进入随机池：两题都可能被抽到。
        Set<String> drawn = new java.util.HashSet<>();
        for (int index = 0; index < 100; index++)
            drawn.add(select(learner, point, Set.of(), 3, KnowledgeQuestionPoolService.Mode.NORMAL).id());
        assertThat(drawn).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void historicalExposureNeverHardLocksRetryAndSingleQuestionPoolStaysUsable() {
        String learner = learner();
        String point = knowledge();
        String seenDifficultyTwo = question(point, 2);
        String freshDifficultyThree = question(point, 3);
        expose(learner, "ancient-official", seenDifficultyTwo, NOW.minus(1, ChronoUnit.HOURS));

        // 全部题都见过以后仍然允许旧题再次出现，不会阻断任务重试。
        Set<String> drawn = new java.util.HashSet<>();
        for (int index = 0; index < 100; index++)
            drawn.add(select(learner, point, Set.of(), 2, KnowledgeQuestionPoolService.Mode.NORMAL).id());
        assertThat(drawn).containsExactlyInAnyOrder(seenDifficultyTwo, freshDifficultyThree);

        String singlePoint = knowledge();
        String onlyQuestion = question(singlePoint, 2);
        expose(learner, "ancient-official", onlyQuestion, NOW.minus(1, ChronoUnit.MINUTES));
        assertThat(select(learner, singlePoint, Set.of(), 2, KnowledgeQuestionPoolService.Mode.NORMAL).id())
                .isEqualTo(onlyQuestion);
    }

    private cn.tihaishitu.catalog.QuestionDto select(String learner, String point, Set<String> seen, int preferred,
                                                     KnowledgeQuestionPoolService.Mode mode) {
        return pool.selectQuestionForLearner(learner,
                new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(
                        point, Set.of(point), seen, preferred, mode));
    }

    private String learner() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                id, "rotation-" + id, "轮换学习者");
        return id;
    }

    private String knowledge() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','分部','章节','core','active','','',0,1)
                """, id, "ROTATION-" + id, "轮换目标");
        return id;
    }

    private String question(String point, int difficulty) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','轮换题','true','解析',?,'published',1)
                """, id, difficulty);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                id, point);
        return id;
    }

    private void expose(String learner, String world, String question, Instant createdAt) {
        expose(learner, world, question, "active", createdAt);
    }

    private void expose(String learner, String world, String question, String status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,created_at)
                VALUES (?,NULL,?,?,?,?,?,?,'auto',?)
                """, UUID.randomUUID().toString(), learner, world, question, "{}", "true", status,
                Timestamp.from(createdAt));
    }
}

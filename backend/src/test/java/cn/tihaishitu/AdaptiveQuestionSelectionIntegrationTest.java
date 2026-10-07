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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 最新规则：正式题抽取不再使用 difficulty / masteryCap / preferred difficulty / exposure 软排序，
 * 只在候选池内随机，并用 Session 的 seenQuestionIds 做硬排除。
 *
 * <p>难度策略仍然保留在两个地方：</p>
 * <ul>
 *   <li>{@link AdaptiveSchedulingPolicy#preferredDifficulty} 仍然计算“软提示难度”，供补救讲练使用；</li>
 *   <li>TRAINING 模式仍然优先低难度，因为它属于 Remedial 流程，不是普通 Formal Question 抽取。</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:adaptive-question-selection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class AdaptiveQuestionSelectionIntegrationTest {
    @Autowired KnowledgeQuestionPoolService pool;
    @Autowired JdbcTemplate jdbc;

    @Test
    void formalDrawIsRandomAndOnlyHonoursSeenExclusion() {
        String learner = learner();
        String point = knowledge();
        Map<Integer, String> questions = new LinkedHashMap<>();
        for (int difficulty = 1; difficulty <= 5; difficulty++)
            questions.put(difficulty, question(point, difficulty));
        Instant now = Instant.parse("2026-10-04T00:00:00Z");
        KnowledgeMasteryModel.State forgotten = state(30, 5, now);
        int standard = AdaptiveSchedulingPolicy.preferredDifficulty(forgotten, 30, "standard");
        int gentle = AdaptiveSchedulingPolicy.preferredDifficulty(forgotten, 30, "gentle");

        // 难度计算本身保持不变。
        assertThat(standard).isEqualTo(2);
        assertThat(gentle).isEqualTo(1);

        // 正式题：全难度都在候选池内随机，不因为 preferred difficulty 把其他难度排除。
        java.util.Set<String> drawn = new java.util.HashSet<>();
        for (int index = 0; index < 200; index++)
            drawn.add(select(learner, point, Set.of(), standard, false).id());
        assertThat(drawn).containsExactlyInAnyOrderElementsOf(questions.values());

        // seenQuestionIds 是硬排除：只剩一道未见题时必定抽到它。
        Set<String> allButOne = new java.util.HashSet<>(questions.values());
        String remaining = questions.get(4);
        allButOne.remove(remaining);
        assertThat(select(learner, point, allButOne, standard, false).id()).isEqualTo(remaining);

        // 本轮全部见过时本轮不再发题（不报 500），由调用方结束这一轮。
        assertThatThrownBy(() -> select(learner, point, new java.util.HashSet<>(questions.values()), standard, false))
                .isInstanceOf(cn.tihaishitu.common.ApiException.class)
                .hasMessageContaining("暂无可用于专项练习的正式题");
    }

    @Test
    void aNewSessionReopensTheWholeRandomPool() {
        String learner = learner();
        String point = knowledge();
        Map<Integer, String> questions = new LinkedHashMap<>();
        for (int difficulty = 1; difficulty <= 3; difficulty++)
            questions.put(difficulty, question(point, difficulty));
        // 上一轮全部练过：跨 run 不再做 exposure 软排序，新 Session 仍然可以重新抽到任意一题。
        insertGraded(learner, point, questions.get(1), "correct", Instant.parse("2026-10-01T08:00:00Z"));
        insertGraded(learner, point, questions.get(2), "correct", Instant.parse("2026-10-02T08:00:00Z"));
        insertGraded(learner, point, questions.get(3), "wrong", Instant.parse("2026-10-03T08:00:00Z"));

        Set<String> drawn = new java.util.HashSet<>();
        for (int index = 0; index < 200; index++)
            drawn.add(select(learner, point, Set.of(), 2, false).id());
        assertThat(drawn).containsExactlyInAnyOrderElementsOf(questions.values());
    }

    @Test
    void trainingStillPrefersLowDifficultyBecauseItIsRemedial() {
        String learner = learner();
        String point = knowledge();
        Map<Integer, String> questions = new LinkedHashMap<>();
        for (int difficulty = 1; difficulty <= 5; difficulty++)
            questions.put(difficulty, question(point, difficulty));

        Set<String> drawn = new java.util.HashSet<>();
        for (int index = 0; index < 100; index++)
            drawn.add(select(learner, point, Set.of(), 5, true).id());
        assertThat(drawn).containsExactlyInAnyOrder(questions.get(1), questions.get(2));

        // 低难度都用尽后，TRAINING 退回到剩余候选中最低的难度，而不是报“前置知识未就绪”。
        assertThat(select(learner, point, Set.of(questions.get(1), questions.get(2)), 5, true).id())
                .isEqualTo(questions.get(3));
    }

    private cn.tihaishitu.catalog.QuestionDto select(String learner, String point, Set<String> seen,
                                                     int preferred, boolean training) {
        return pool.selectQuestionForLearner(learner, new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(
                point, Set.of(point), seen, preferred, training
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

    /** 直接写入历史正式作答，用于验证“新 Session 重新进入随机池”与已练题不再被硬排除。 */
    private void insertGraded(String learner, String point, String question, String outcome, Instant at) {
        jdbc.update("""
                INSERT INTO study_attempt(id,learner_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,
                    question_difficulty,answered_at)
                VALUES (?,?,?,'{}','true','graded','auto','automatic',?,?,'normal',2,?)
                """, UUID.randomUUID().toString(), learner, question, outcome, point,
                java.sql.Timestamp.from(at));
    }
}

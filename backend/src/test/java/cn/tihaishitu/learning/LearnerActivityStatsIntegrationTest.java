package cn.tihaishitu.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR7 进度统计 V3：统一有效 Attempt 口径的集成测试。
 *
 * <p>覆盖有效性、时间（Asia/Shanghai 业务日）与当前 Selected Books 范围三条主线。
 * 时间基准取真实「现在」，因此业务日断言始终落在真实时区边界上。</p>
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-activity;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerActivityStatsIntegrationTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired JdbcTemplate jdbc;
    @Autowired LearnerActivityStatsService activity;

    private final Instant now = Instant.now();
    private final java.time.LocalDate today = Instant.now().atZone(ZONE).toLocalDate();

    @Test
    void countsEveryFormalScenarioOnceAndSeparatesRevealedOnlyFromRealWrong() {
        String learner = learner();
        String point = knowledge("统一口径知识点");
        BookFixture book = book("统一口径文集");
        member(book.id(), book.root(), point, 0);
        select(learner, book.id());
        String question = question();

        // 全部正式场景：世界随机、章节练习、知识点专项、错题重做、错题快练。
        // 同一道题的不同有效 Attempt 分别计数。
        attempt(learner, "ancient-official", null, question, point, "graded", at(10, 0), "correct");
        attempt(learner, null, practice(learner, point, "chapter_drill"), question, point, "graded", at(11, 0), "correct");
        attempt(learner, null, practice(learner, point, "knowledge_drill"), question, point, "graded", at(12, 0), "partial");
        attempt(learner, null, practice(learner, point, "wrong_review"), question, point, "graded", at(13, 0), "wrong");
        attempt(learner, null, practice(learner, point, "wrong_drill"), question, point, "graded", at(14, 0), "wrong");
        attempt(learner, null, practice(learner, point, "wrong_drill"), question, point, "graded", at(15, 0), "correct");

        // 仅查看答案：算 1 次，但绝不能被写成 wrong。
        reveal(learner, null, question, point, at(16, 0));
        // 打开后直接退出、Legacy 无 Learner、Remedial 子题：都不计。
        attempt(learner, null, null, question, point, "active", null, null);
        legacyAttempt(question, point, at(17, 0));
        remedialAttempt(learner, question, point, at(18, 0));

        LearnerActivityStatsService.ActivityView view = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);
        LearnerActivityStatsService.Metrics metrics = view.metrics();

        assertThat(metrics.totalEffectiveAttempts()).isEqualTo(7);
        assertThat(metrics.totalCorrectAttempts()).isEqualTo(3);
        assertThat(metrics.todayEffectiveAttempts()).isEqualTo(7);
        assertThat(metrics.totalKnowledgePoints()).isEqualTo(1);
        assertThat(metrics.touchedKnowledgePoints()).isEqualTo(1);
        assertThat(metrics.activeStudyDays7d()).isEqualTo(1);
        assertThat(view.outcomes()).isEqualTo(new LearnerActivityStatsService.OutcomeSummary(3, 1, 2, 1));
        // 结果分布四类之和必须与累计有效答题一致。
        assertThat(view.outcomes().correct() + view.outcomes().partial() + view.outcomes().wrong()
                + view.outcomes().revealedOnly()).isEqualTo(metrics.totalEffectiveAttempts());
        // 七日每日条数之和必须等于七日窗口内的有效答题总数（这里全部落在今天）。
        assertThat(view.daily()).hasSize(7);
        assertThat(view.daily()).extracting(LearnerActivityStatsService.DailyActivity::date)
                .containsExactly(today.minusDays(6), today.minusDays(5), today.minusDays(4), today.minusDays(3),
                        today.minusDays(2), today.minusDays(1), today);
        assertThat(view.daily().stream().mapToInt(LearnerActivityStatsService.DailyActivity::effectiveAttempts).sum())
                .isEqualTo(7);
    }

    @Test
    void countsOneAttemptOnceWhenItWasRevealedBeforeSelfAssessment() {
        String learner = learner();
        String point = knowledge("先看答案再自评");
        BookFixture book = book("自评文集");
        member(book.id(), book.root(), point, 0);
        select(learner, book.id());
        String question = question();

        // 同一次 Attempt：先 reveal，再自评 partial。必须只计 1 次，结果采用真实 assessment。
        String attemptId = reveal(learner, null, question, point, at(9, 0));
        jdbc.update("""
                UPDATE study_attempt SET status='graded',answered_at=?,grading_source='self',assessment='partial'
                 WHERE id=?
                """, Timestamp.from(at(21, 0)), attemptId);

        // 另一次 Attempt 只 reveal 未评分。
        reveal(learner, null, question, point, at(10, 0));

        LearnerActivityStatsService.ActivityView view = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);

        assertThat(view.metrics().totalEffectiveAttempts()).isEqualTo(2);
        assertThat(view.metrics().totalCorrectAttempts()).isZero();
        assertThat(view.outcomes()).isEqualTo(new LearnerActivityStatsService.OutcomeSummary(0, 1, 0, 1));
        assertThat(view.metrics().touchedKnowledgePoints()).isEqualTo(1);
    }

    @Test
    void keepsTheFirstEffectiveActionDayStableAcrossMidnightRevealAndGrading() {
        // 自评题在业务日 A 的最后一小时 reveal、在业务日 B 自评时，有效事件仍然属于 A。
        String learner = learner();
        String point = knowledge("跨天自评知识点");
        BookFixture book = book("跨天文集");
        member(book.id(), book.root(), point, 0);
        select(learner, book.id());
        String question = question();

        Instant revealAt = today.minusDays(1).atTime(23, 30).atZone(ZONE).toInstant();
        String attemptId = reveal(learner, null, question, point, revealAt);
        jdbc.update("""
                UPDATE study_attempt SET status='graded',answered_at=?,grading_source='self',assessment='correct'
                 WHERE id=?
                """, Timestamp.from(revealAt.plusSeconds(3600)), attemptId);

        LearnerActivityStatsService.ActivityView view = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);

        assertThat(view.metrics().totalEffectiveAttempts()).isEqualTo(1);
        assertThat(view.metrics().todayEffectiveAttempts()).isZero();
        assertThat(view.metrics().activeStudyDays7d()).isEqualTo(1);
        assertThat(view.daily().stream()
                .filter(day -> day.date().equals(today.minusDays(1)))
                .findFirst().orElseThrow().effectiveAttempts()).isEqualTo(1);
        assertThat(view.daily().stream()
                .filter(day -> day.date().equals(today))
                .findFirst().orElseThrow().effectiveAttempts()).isZero();
    }

    @Test
    void excludesUnusableLegacyRecordsInsteadOfInventingAnOutcome() {
        String learner = learner();
        String point = knowledge("历史脏数据知识点");
        BookFixture book = book("历史文集");
        member(book.id(), book.root(), point, 0);
        select(learner, book.id());
        String question = question();

        // graded 但 assessment 缺失 / 非法：无法判定结果，排除而不是编造。
        attempt(learner, null, null, question, point, "graded", at(9, 0), null);
        attempt(learner, null, null, question, point, "graded", at(10, 0), "unknown");
        // graded 但没有任何合法时间：不虚构日期。
        attempt(learner, null, null, question, point, "graded", null, "correct");
        // 一道被下架的题目：historical record 保留，但不再是正式事实来源。
        String retired = question();
        jdbc.update("UPDATE question_resource SET status='archived' WHERE id=?", retired);
        attempt(learner, null, null, retired, point, "graded", at(11, 0), "correct");
        // 唯一一条健康记录。
        attempt(learner, null, null, question, point, "graded", at(12, 0), "correct");

        LearnerActivityStatsService.ActivityView view = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);

        assertThat(view.metrics().totalEffectiveAttempts()).isEqualTo(1);
        assertThat(view.metrics().totalCorrectAttempts()).isEqualTo(1);
        assertThat(view.outcomes()).isEqualTo(new LearnerActivityStatsService.OutcomeSummary(1, 0, 0, 0));
    }

    @Test
    void hidesAndRestoresHistoryWhenSelectedBooksChangeWithoutTouchingAttempts() {
        String learner = learner();
        String mathPoint = knowledge("数学知识点");
        String csPoint = knowledge("计算机知识点");
        BookFixture math = book("数学文集");
        BookFixture cs = book("计算机文集");
        member(math.id(), math.root(), mathPoint, 0);
        member(cs.id(), cs.root(), csPoint, 0);
        select(learner, math.id());
        select(learner, cs.id());

        String question = question();
        attempt(learner, null, null, question, mathPoint, "graded", at(9, 0), "correct");
        attempt(learner, null, null, question, csPoint, "graded", at(10, 0), "correct");

        LearnerActivityStatsService.ActivityView both = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);
        assertThat(both.metrics().totalEffectiveAttempts()).isEqualTo(2);
        assertThat(both.metrics().totalKnowledgePoints()).isEqualTo(2);
        assertThat(both.metrics().touchedKnowledgePoints()).isEqualTo(2);

        // 取消计算机文集：只隐藏历史，不删除任何 Attempt。
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=? AND bank_id=?", learner, cs.id());
        LearnerActivityStatsService.ActivityView mathOnly = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);
        assertThat(mathOnly.metrics().totalEffectiveAttempts()).isEqualTo(1);
        assertThat(mathOnly.metrics().totalKnowledgePoints()).isEqualTo(1);
        assertThat(mathOnly.metrics().touchedKnowledgePoints()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_attempt WHERE learner_id=?",
                Integer.class, learner)).isEqualTo(2);

        // 重新加入计算机文集：既有历史立即恢复。
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learner, cs.id());
        LearnerActivityStatsService.ActivityView restored = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);
        assertThat(restored.metrics().totalEffectiveAttempts()).isEqualTo(2);
        assertThat(restored.metrics().totalKnowledgePoints()).isEqualTo(2);
        assertThat(restored.metrics().touchedKnowledgePoints()).isEqualTo(2);
    }

    @Test
    void deduplicatesSharedKnowledgePointsAcrossBooksAndAttributesOnlyTheFrozenTarget() {
        String learner = learner();
        String shared = knowledge("两本文集共享知识点");
        String other = knowledge("同一道题的另一知识点");
        BookFixture first = book("共享文集甲");
        BookFixture second = book("共享文集乙");
        member(first.id(), first.root(), shared, 0);
        member(second.id(), second.root(), shared, 0);
        member(first.id(), first.root(), other, 1);
        select(learner, first.id());
        select(learner, second.id());

        // 一道题同时关联两个知识点，但 Attempt 的冻结 target 只有一个。
        String question = question();
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'auxiliary',1)",
                question, other);
        attempt(learner, null, null, question, shared, "graded", at(9, 0), "correct");

        LearnerActivityStatsService.ActivityView view = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);

        // 多本文集共享同一知识点不翻倍；另一知识点没有冻结 target，不获得接触记录。
        assertThat(view.metrics().totalKnowledgePoints()).isEqualTo(2);
        assertThat(view.metrics().touchedKnowledgePoints()).isEqualTo(1);
        assertThat(view.metrics().totalEffectiveAttempts()).isEqualTo(1);
    }

    @Test
    void scopesTheSevenDayWindowToShanghaiBusinessDays() {
        String learner = learner();
        String point = knowledge("业务日边界知识点");
        BookFixture book = book("边界文集");
        member(book.id(), book.root(), point, 0);
        select(learner, book.id());
        String question = question();

        // 七天窗口的起点与终点：起点当天 00:00 计入，窗口前一天 23:59 排除。
        String windowStartAttempt = attempt(learner, null, null, question, point, "graded",
                today.minusDays(6).atStartOfDay(ZONE).toInstant(), "correct");
        attempt(learner, null, null, question, point, "graded",
                today.minusDays(6).atStartOfDay(ZONE).minusSeconds(60).toInstant(), "correct");
        // 今天 00:00 计入。
        attempt(learner, null, null, question, point, "graded",
                today.atStartOfDay(ZONE).toInstant(), "correct");

        LearnerActivityStatsService.ActivityView view = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);

        assertThat(windowStartAttempt).isNotBlank();
        assertThat(view.metrics().totalEffectiveAttempts()).isEqualTo(3);
        assertThat(view.metrics().todayEffectiveAttempts()).isEqualTo(1);
        assertThat(view.metrics().activeStudyDays7d()).isEqualTo(2);
        assertThat(view.metrics().touchedKnowledgePoints()).isEqualTo(1);
        assertThat(view.daily()).hasSize(7);
        // 累计指标是全历史：七日窗口外的 Attempt 依然计入累计，但不进入任何一天。
        assertThat(view.daily().stream().mapToInt(LearnerActivityStatsService.DailyActivity::effectiveAttempts).sum())
                .isEqualTo(2);
    }

    @Test
    void returnsAZeroedUnifiedWindowForAnEmptyLearningScope() {
        String learner = learner();

        LearnerActivityStatsService.ActivityView view = activity.activityAt(learner, now,
                LearnerActivityStatsService.WINDOW_DAYS);

        assertThat(view.metrics()).isEqualTo(new LearnerActivityStatsService.Metrics(0, 0, 0, 0, 0, 0));
        assertThat(view.outcomes()).isEqualTo(new LearnerActivityStatsService.OutcomeSummary(0, 0, 0, 0));
        assertThat(view.daily()).hasSize(7);
        assertThat(view.daily()).allSatisfy(day ->
                assertThat(day.effectiveAttempts()).isZero());
    }

    private Instant at(int hour, int minute) {
        return today.atTime(hour, minute).atZone(ZONE).toInstant();
    }

    private String learner() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,?,?,'x','active',1)",
                id, "activity-" + id, "活动统计学习者");
        jdbc.update("INSERT INTO learner_study_profile(learner_id,pace,difficulty,focus_mode,revision) VALUES (?,'normal','standard','auto',1)", id);
        return id;
    }

    private String knowledge(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','测试分科','测试章节','core','active','','',0,1)
                """, id, "ACTIVITY-" + id, name);
        return id;
    }

    private BookFixture book(String name) {
        String id = UUID.randomUUID().toString(), root = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,?,TRUE,1,1)", id, name, name + "说明");
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'ROOT','总章','',0,1)", root, id);
        return new BookFixture(id, root);
    }

    private void member(String book, String chapter, String point, int order) {
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",
                book, point, chapter, order);
        // 可学习知识点必须关联至少一道 published Formal Parent Question。
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                question(), point);
    }

    private void select(String learner, String book) {
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
    }

    private String practice(String learner, String point, String intent) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_practice_session(id,learner_id,intent,target_knowledge_point_id,status,revision) VALUES (?,?,?,?,'active',1)",
                id, learner, intent, point);
        return id;
    }

    private String question() {
        return question("true_false");
    }

    private String question(String type) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom',?,?,'auto','活动题','true','解析',2,'published',1)
                """, id, type, type);
        return id;
    }

    private String attempt(String learner, String world, String practice, String question, String point,
                           String status, Instant answeredAt, String assessment) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,grading_source,assessment,
                    target_knowledge_point_id,evidence_mode,question_difficulty,answered_at)
                VALUES (?,NULL,?,?,?,?,'{}','true',?,'auto',?,?,?,'normal',2,?)
                """, id, learner, world, practice, question, status,
                "graded".equals(status) ? "automatic" : null,
                "graded".equals(status) ? assessment : null, point,
                answeredAt == null ? null : Timestamp.from(answeredAt));
        return id;
    }

    /** 自评题先查看参考解析：status=revealed，不写 assessment，也不写 grading / 错题本。 */
    private String reveal(String learner, String practice, String question, String point, Instant revealedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,assessment,
                    answer_revealed_at,target_knowledge_point_id,evidence_mode,question_difficulty)
                VALUES (?,NULL,?,NULL,?,?,'{}','true','revealed','self_assessment',NULL,?,?,'normal',2)
                """, id, learner, practice, question, Timestamp.from(revealedAt), point);
        return id;
    }

    /** Legacy /games/** 路径：game_id 存档、没有 Learner 身份，不计入任何统计。 */
    private void legacyAttempt(String question, String point, Instant answeredAt) {
        String game = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO game_save(id,player_name,player_title,payload_json,revision) VALUES (?,'旧玩家','旧存档','{}',1)", game);
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,question_id,question_snapshot_json,
                    standard_answer_json,status,grading_mode,grading_source,assessment,
                    target_knowledge_point_id,evidence_mode,question_difficulty,answered_at)
                VALUES (?,?,NULL,?,'{}','true','graded','auto','automatic','wrong',?,'normal',2,?)
                """, UUID.randomUUID().toString(), game, question, point, Timestamp.from(answeredAt));
    }

    /** Remedial 子题：parent_question_id 非空，是正式题的子题，不计入统计。 */
    private void remedialAttempt(String learner, String parentQuestion, String point, Instant answeredAt) {
        String child = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,
                    parent_question_id,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto','子题','true','解析',2,'published',?,1)
                """, child, parentQuestion);
        attempt(learner, null, null, child, point, "graded", answeredAt, "correct");
    }

    private record BookFixture(String id, String root) {}
}

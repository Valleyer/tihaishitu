package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.learner.LearnerStore;
import cn.tihaishitu.learning.PracticeBusinessDay;
import cn.tihaishitu.learning.PracticeDrawReason;
import cn.tihaishitu.learning.PracticeSelectionStore;
import cn.tihaishitu.learning.RandomPracticeSelector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR3 RANDOM 选题策略：KP-first + 每日硬去重 + oldest / wrong lane。
 *
 * <p>长期规则：</p>
 *
 * <pre>
 * 1. 先选 target KnowledgePoint，再在该 KP 内选题
 * 2. 当天第一题从所有“今天仍有候选”的 KP 中纯随机选一个
 * 3. 上一题 correct 优先换 KP；wrong / partial 优先留在原 target KP
 * 4. 同一 Learner × 同一 Asia/Shanghai 业务日，同一 Question 最多出一次
 * 5. 每个 Learner × KnowledgePoint 内 oldest / wrong 交替，wrong 空则 fallback oldest
 * </pre>
 *
 * <p>业务日一律显式按 Asia/Shanghai 计算，不使用机器默认时区。</p>
 */
@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:random-selection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RandomPracticeSelectionIntegrationTest {
    /** 当日正午（Asia/Shanghai 业务日内）。 */
    private static final Instant TODAY = PracticeBusinessDay.startOfDay(Instant.now()).plus(Duration.ofHours(12));
    /** 上一业务日正午：一定落在昨天，不会因为机器时区漂移到今天。 */
    private static final Instant YESTERDAY = PracticeBusinessDay.startOfDay(Instant.now()).minus(Duration.ofHours(12));
    private static final Instant TEN_DAYS_AGO = TODAY.minus(Duration.ofDays(10));
    private static final Instant FIVE_DAYS_AGO = TODAY.minus(Duration.ofDays(5));
    private static final Instant THREE_DAYS_AGO = TODAY.minus(Duration.ofDays(3));
    private static final Instant ONE_DAY_AGO = TODAY.minus(Duration.ofDays(1));

    @Autowired JdbcTemplate jdbc; @Autowired RandomPracticeSelector selector;
    @Autowired PracticeSelectionStore selections;
    @Autowired LearnerStore learners; @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;

    // ---------------------------------------------------------------- KP transition

    @Test void firstRandomOfTheBusinessDayPicksFromKnowledgePointsThatStillHaveCandidates() {
        String book = book("RANDOM-FIRST");
        String k1 = point(book, "FIRST-K1", 0), k2 = point(book, "FIRST-K2", 1);
        question(k1, "1");
        question(k2, "1");
        String learner = learner("random-first-of-day");
        assertThat(selector.remainingToday(learner, Set.of(k1, k2), Set.of())).isEqualTo(2);

        // 纯随机：不做 KP 权重 / Mastery 排序，所以两个 KP 都必须有机会被选中。
        Set<String> drawnPoints = new HashSet<>();
        for (int round = 0; round < 40; round++)
            drawnPoints.add(selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow().targetKnowledgePointId());
        assertThat(drawnPoints).containsExactlyInAnyOrder(k1, k2);
        assertThat(selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow().drawReason())
                .isEqualTo(PracticeDrawReason.OLDEST);
    }

    @Test void correctPreviousRandomSwitchesToAnotherKnowledgePoint() {
        String book = book("RANDOM-CORRECT");
        String k1 = point(book, "CORRECT-K1", 0), k2 = point(book, "CORRECT-K2", 1);
        String k1First = question(k1, "1");
        question(k1, "2");
        question(k2, "1");
        question(k2, "2");
        String learner = learner("random-correct-switch");
        attempt(learner, k1, k1First, "graded", "correct", "random", PracticeDrawReason.OLDEST, TODAY);

        for (int round = 0; round < 10; round++)
            assertThat(selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow().targetKnowledgePointId())
                    .isEqualTo(k2);
    }

    @Test void singleEligibleKnowledgePointStaysOnTheSamePointAfterCorrectWithoutDeadlock() {
        String book = book("RANDOM-SINGLE");
        String k1 = point(book, "SINGLE-K1", 0);
        String first = question(k1, "1");
        question(k1, "2");
        String learner = learner("random-single-point");
        attempt(learner, k1, first, "graded", "correct", "random", PracticeDrawReason.OLDEST, TODAY);

        var selection = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(selection.targetKnowledgePointId()).isEqualTo(k1);
        assertThat(selection.questionId()).isNotEqualTo(first);
    }

    @Test void wrongPreviousRandomStaysOnTheSamePointAndDrawsAnotherQuestion() {
        String book = book("RANDOM-WRONG-STAY");
        String k1 = point(book, "STAY-K1", 0), k2 = point(book, "STAY-K2", 1);
        String k1First = question(k1, "1");
        question(k1, "2");
        question(k2, "1");
        question(k2, "2");
        String learner = learner("random-wrong-stay");
        attempt(learner, k1, k1First, "graded", "wrong", "random", PracticeDrawReason.OLDEST, TODAY);

        for (int round = 0; round < 10; round++) {
            var selection = selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
            assertThat(selection.targetKnowledgePointId()).isEqualTo(k1);
            assertThat(selection.questionId()).isNotEqualTo(k1First);
        }
    }

    @Test void partialIsHandledExactlyLikeWrong() {
        String book = book("RANDOM-PARTIAL");
        String k1 = point(book, "PARTIAL-K1", 0), k2 = point(book, "PARTIAL-K2", 1);
        String k1First = question(k1, "1");
        question(k1, "2");
        question(k2, "1");
        question(k2, "2");
        String learner = learner("random-partial-stay");
        attempt(learner, k1, k1First, "graded", "partial", "random", PracticeDrawReason.OLDEST, TODAY);

        assertThat(selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow().targetKnowledgePointId())
                .isEqualTo(k1);
    }

    @Test void wrongKnowledgePointWithoutRemainingQuestionsSwitchesToAnotherPoint() {
        String book = book("RANDOM-WRONG-SWITCH");
        String k1 = point(book, "SWITCH-K1", 0), k2 = point(book, "SWITCH-K2", 1);
        String onlyK1Question = question(k1, "1");
        question(k2, "1");
        question(k2, "2");
        String learner = learner("random-wrong-switch");
        attempt(learner, k1, onlyK1Question, "graded", "wrong", "random", PracticeDrawReason.OLDEST, TODAY);

        assertThat(selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow().targetKnowledgePointId())
                .isEqualTo(k2);
    }

    @Test void persistedCursorDeterminesLatestAttemptWhenTimestampTiesAndUuidOrderIsReversed() {
        String book = book("RANDOM-LATEST-CURSOR");
        String k1 = point(book, "LATEST-K1", 0), k2 = point(book, "LATEST-K2", 1);
        String oldQuestion = question(k1, "old"), latestQuestion = question(k2, "latest");
        question(k1, "remaining-k1");
        question(k2, "remaining-k2");
        String learner = learner("random-latest-cursor");
        String oldId = "ffffffff-ffff-ffff-ffff-ffffffffffff";
        String latestId = "00000000-0000-0000-0000-000000000001";

        // 同一秒先写 A 后写 B，但 A.id 字典序更大；UUID 顺序与真实发题顺序故意相反。
        attempt(oldId, learner, k1, oldQuestion, "graded", "wrong",
                "random", PracticeDrawReason.OLDEST, TODAY);
        attempt(latestId, learner, k2, latestQuestion, "graded", "correct",
                "random", PracticeDrawReason.OLDEST, TODAY);
        selections.recordLastRandomAttempt(learner, latestId);

        assertThat(selections.lastRandomAttempt(learner)).contains(
                new PracticeSelectionStore.LastRandomAttempt(k2, "correct"));
        // 最新 B=correct，所以必须排除 B 的 K2、切到 K1，并消费一次池轮换；
        // 旧 UUID 排序会误读 A=wrong，虽然也留在 K1，却不会消费轮换。
        var next = selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
        assertThat(next.targetKnowledgePointId()).isEqualTo(k1);
        assertThat(next.requestedPool()).isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.ALL);
    }

    @Test void activeLatestAttemptWithTiedTimestampDoesNotFallBackToOlderAssessment() {
        assertTiedUngradedLatestAttemptWins("active");
    }

    @Test void revealedLatestAttemptWithTiedTimestampDoesNotFallBackToOlderAssessment() {
        assertTiedUngradedLatestAttemptWins("revealed");
    }

    private void assertTiedUngradedLatestAttemptWins(String status) {
        String book = book("RANDOM-LATEST-" + status);
        String k1 = point(book, "LATEST-NULL-K1", 0), k2 = point(book, "LATEST-NULL-K2", 1);
        String oldQuestion = question(k1, "old"), latestQuestion = question(k2, "latest");
        question(k1, "remaining-k1");
        question(k2, "remaining-k2");
        String learner = learner("random-latest-" + status);
        String oldId = "active".equals(status)
                ? "ffffffff-ffff-ffff-ffff-fffffffffff0"
                : "ffffffff-ffff-ffff-ffff-fffffffffff1";
        String latestId = "active".equals(status)
                ? "00000000-0000-0000-0000-000000000002"
                : "00000000-0000-0000-0000-000000000004";
        attempt(oldId, learner, k1, oldQuestion, "graded", "correct",
                "random", PracticeDrawReason.OLDEST, TODAY);
        attempt(latestId, learner, k2, latestQuestion, status, null,
                "random", PracticeDrawReason.OLDEST, TODAY);
        selections.recordLastRandomAttempt(learner, latestId);

        assertThat(selections.lastRandomAttempt(learner)).contains(
                new PracticeSelectionStore.LastRandomAttempt(k2, null));
        // assessment=null 必须走未判分分支，不能回退读取旧 A=correct，也不消费新池轮换。
        assertThat(selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow().requestedPool()).isNull();
    }

    @Test void ambiguousLegacyTimestampTieIsNotGuessedFromUuidOrder() {
        String book = book("RANDOM-LEGACY-TIE");
        String k1 = point(book, "LEGACY-TIE-K1", 0), k2 = point(book, "LEGACY-TIE-K2", 1);
        String learner = learner("random-legacy-tie");
        attempt("ffffffff-ffff-ffff-ffff-ffffffffffe0", learner, k1, question(k1, "old"),
                "graded", "wrong", "random", PracticeDrawReason.OLDEST, TODAY);
        attempt("00000000-0000-0000-0000-000000000003", learner, k2, question(k2, "latest"),
                "graded", "correct", "random", PracticeDrawReason.OLDEST, TODAY);

        // 升级前 schema 没有真实先后事实；安全地返回未知，不再让随机 UUID 替系统作决定。
        assertThat(selections.lastRandomAttempt(learner)).isEmpty();
    }

    @Test void poolRotationStartsWithAllThenWrongAndWrongOrPartialDoesNotConsume() {
        String book = book("RANDOM-POOL-ROTATION");
        String k1 = point(book, "POOL-K1", 0), k2 = point(book, "POOL-K2", 1);
        String k1Wrong = question(k1, "wrong"), k1Pad = question(k1, "pad");
        question(k1, "remain");
        String k2First = question(k2, "first"), k2Pad = question(k2, "pad");
        String learner = learner("random-pool-rotation");
        wrongBook(learner, k1, k1Wrong, "active");
        // 模拟升级前已经存在的 RANDOM 历史；没有 V23 row 时仍从 ALL 开始。
        attempt(learner, k1, k1Pad, "graded", "correct", "random", PracticeDrawReason.OLDEST, TODAY);

        var all = selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
        assertThat(all.requestedPool()).isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.ALL);
        assertThat(all.targetKnowledgePointId()).isEqualTo(k2);
        selector.recordPoolRotation(learner, all);
        attempt(learner, k2, all.questionId(), "graded", "correct", "random", all.drawReason(), TODAY.plusSeconds(1));

        var wrong = selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
        assertThat(wrong.requestedPool()).isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.WRONG);
        assertThat(wrong.targetKnowledgePointId()).isEqualTo(k1);
        selector.recordPoolRotation(learner, wrong);
        attempt(learner, k1, wrong.questionId(), "graded", "partial", "random", wrong.drawReason(), TODAY.plusSeconds(2));

        var stays = selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
        assertThat(stays.requestedPool()).isNull();
        assertThat(stays.targetKnowledgePointId()).isEqualTo(k1);
        selector.recordPoolRotation(learner, stays);
        assertThat(jdbc.queryForObject("SELECT selection_count FROM learner_random_kp_rotation WHERE learner_id=?",
                Long.class, learner)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT last_requested_pool FROM learner_random_kp_rotation WHERE learner_id=?",
                String.class, learner)).isEqualTo("wrong");
    }

    @Test void firstRandomOfANewBusinessDayContinuesThePersistedRotation() {
        String book = book("RANDOM-CROSS-DAY-ROTATION");
        String k1 = point(book, "CROSS-DAY-K1", 0), k2 = point(book, "CROSS-DAY-K2", 1);
        String old = question(k1, "old");
        String wrongQuestion = question(k2, "wrong");
        String learner = learner("random-cross-day-rotation");
        wrongBook(learner, k2, wrongQuestion, "active");
        attempt(learner, k1, old, "graded", "wrong", "random", PracticeDrawReason.OLDEST, YESTERDAY);
        jdbc.update("INSERT INTO learner_random_kp_rotation(learner_id,last_requested_pool,selection_count) VALUES (?,'all',1)", learner);

        // 重建 Store / Selector 模拟服务重启：轮换事实只能来自数据库，不能依赖 JVM 内存。
        var restartedSelector = new RandomPracticeSelector(new PracticeSelectionStore(jdbc));
        var selection = restartedSelector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
        assertThat(selection.requestedPool()).isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.WRONG);
        assertThat(selection.targetKnowledgePointId()).isEqualTo(k2);
    }

    @Test void wrongPoolFallbackStillConsumesItsSlotAndKeepsCorrectSwitchPriority() {
        String book = book("RANDOM-WRONG-POOL-FALLBACK");
        String k1 = point(book, "POOL-FALLBACK-K1", 0), k2 = point(book, "POOL-FALLBACK-K2", 1);
        String previous = question(k1, "previous");
        String wrongOnlyOnPrevious = question(k1, "wrong");
        question(k2, "other");
        String learner = learner("random-wrong-pool-fallback");
        wrongBook(learner, k1, wrongOnlyOnPrevious, "active");
        attempt(learner, k1, previous, "graded", "correct", "random", PracticeDrawReason.OLDEST, TODAY);
        jdbc.update("INSERT INTO learner_random_kp_rotation(learner_id,last_requested_pool,selection_count) VALUES (?,'all',1)", learner);

        var selection = selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
        assertThat(selection.requestedPool()).isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.WRONG);
        assertThat(selection.targetKnowledgePointId()).isEqualTo(k2);
        selector.recordPoolRotation(learner, selection);
        assertThat(selections.nextRequestedKnowledgePool(learner))
                .isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.ALL);
    }

    @Test void rotationStateIsIsolatedPerLearner() {
        String first = learner("random-pool-user-a"), second = learner("random-pool-user-b");
        selections.recordRequestedKnowledgePool(first, PracticeSelectionStore.RequestedKnowledgePool.ALL);
        assertThat(selections.nextRequestedKnowledgePool(first))
                .isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.WRONG);
        assertThat(selections.nextRequestedKnowledgePool(second))
                .isEqualTo(PracticeSelectionStore.RequestedKnowledgePool.ALL);
    }

    @Test void wrongKnowledgePoolIsDistinctAtKnowledgePointLevelNotWeightedByWrongQuestionCount() {
        String book = book("RANDOM-WRONG-KP-UNIFORM");
        String many = point(book, "WRONG-MANY", 0), one = point(book, "WRONG-ONE", 1);
        String learner = learner("random-wrong-kp-uniform");
        for (int index = 0; index < 5; index++) {
            String question = question(many, "many-" + index);
            wrongBook(learner, many, question, "active");
        }
        String oneQuestion = question(one, "one");
        wrongBook(learner, one, oneQuestion, "active");

        assertThat(selections.activeWrongKnowledgePointIds(learner, Set.of(many, one)))
                .containsExactlyInAnyOrder(many, one);
    }

    @Test void learnerLockSerializesConcurrentRotationConsumption() throws Exception {
        String learner = learner("random-pool-concurrent");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var tasks = List.of(0, 1).stream().map(ignored -> executor.submit(() -> {
                start.await();
                return new TransactionTemplate(transactions).execute(status -> {
                    learners.lockForUpdate(learner);
                    var requested = selections.nextRequestedKnowledgePool(learner);
                    selections.recordRequestedKnowledgePool(learner, requested);
                    return requested;
                });
            })).toList();
            start.countDown();
            assertThat(List.of(tasks.get(0).get(), tasks.get(1).get()))
                    .containsExactlyInAnyOrder(PracticeSelectionStore.RequestedKnowledgePool.ALL,
                            PracticeSelectionStore.RequestedKnowledgePool.WRONG);
            assertThat(jdbc.queryForObject("SELECT selection_count FROM learner_random_kp_rotation WHERE learner_id=?",
                    Long.class, learner)).isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void failedTransactionRollsBackRotationConsumption() {
        String learner = learner("random-pool-rollback");
        String book = book("RANDOM-CURSOR-ROLLBACK");
        String point = point(book, "ROLLBACK-KP", 0);
        String question = question(point, "rollback");
        String attemptId = UUID.randomUUID().toString();
        attempt(attemptId, learner, point, question, "active", null,
                "random", PracticeDrawReason.OLDEST, TODAY);
        var selection = new RandomPracticeSelector.Selection(question, point,
                PracticeDrawReason.OLDEST, PracticeSelectionStore.RequestedKnowledgePool.ALL);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            learners.lockForUpdate(learner);
            selector.recordPersistedAttempt(learner, attemptId, selection);
            throw new IllegalStateException("simulate world persistence failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_random_kp_rotation WHERE learner_id=?",
                Integer.class, learner)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_random_attempt_cursor WHERE learner_id=?",
                Integer.class, learner)).isZero();
    }

    // ---------------------------------------------------------------- daily quota

    @Test void sameQuestionIsOnlyDrawnOncePerBusinessDayAndActiveOrRevealedAlsoCounts() {
        String book = book("RANDOM-QUOTA");
        String k1 = point(book, "QUOTA-K1", 0);
        String q1 = question(k1, "1"), q2 = question(k1, "2");
        String learner = learner("random-quota-today");
        // active / revealed 都算“今天已经出过”：不能等 answered_at 才去重。
        attempt(learner, k1, q1, "active", null, "random", PracticeDrawReason.OLDEST, TODAY);
        attempt(learner, k1, q2, "revealed", null, "random", PracticeDrawReason.OLDEST, TODAY);

        assertThat(selector.remainingToday(learner, Set.of(k1), Set.of())).isZero();
        assertThat(selector.select(learner, Set.of(k1), Set.of())).isEmpty();
    }

    @Test void previousBusinessDayAttemptsDoNotConsumeTodaysQuota() {
        String book = book("RANDOM-RESET");
        String k1 = point(book, "RESET-K1", 0);
        String q1 = question(k1, "1"), q2 = question(k1, "2");
        String learner = learner("random-quota-yesterday");
        attempt(learner, k1, q1, "graded", "correct", "random", PracticeDrawReason.OLDEST, YESTERDAY);
        attempt(learner, k1, q2, "graded", "wrong", "random", PracticeDrawReason.WRONG_FALLBACK, YESTERDAY);

        assertThat(selector.remainingToday(learner, Set.of(k1), Set.of())).isEqualTo(2);
        assertThat(selector.select(learner, Set.of(k1), Set.of()).orElseThrow().questionId()).isIn(q1, q2);
    }

    @Test void chapterKnowledgeAndWrongPracticeDoNotConsumeRandomQuota() {
        String book = book("RANDOM-ISOLATION");
        String k1 = point(book, "ISOLATION-K1", 0);
        String q1 = question(k1, "1"), q2 = question(k1, "2");
        String learner = learner("random-quota-isolation");
        attempt(learner, k1, q1, "graded", "correct", "chapter", null, TODAY);
        attempt(learner, k1, q2, "graded", "wrong", "knowledge", null, TODAY);
        attempt(learner, k1, q1, "graded", "wrong", "wrong", null, TODAY);

        assertThat(selector.remainingToday(learner, Set.of(k1), Set.of())).isEqualTo(2);
        assertThat(selector.select(learner, Set.of(k1), Set.of()).orElseThrow().questionId()).isIn(q1, q2);
    }

    /**
     * 发题后完全没有 grading 结果（active / revealed 未自评）时：
     * 该 Question 仍然占用当天 quota，但 assessment=null 不得被推断成 wrong 而锁定同一 KP。
     */
    @Test void ungradedActivePreviousRandomAttemptDoesNotDriveTheKnowledgePointTransition() {
        assertUngradedAttemptDoesNotLockTheKnowledgePoint("active");
    }

    @Test void ungradedRevealedPreviousRandomAttemptDoesNotDriveTheKnowledgePointTransition() {
        assertUngradedAttemptDoesNotLockTheKnowledgePoint("revealed");
    }

    private void assertUngradedAttemptDoesNotLockTheKnowledgePoint(String status) {
        String book = book("RANDOM-UNGRADED-" + status);
        String k1 = point(book, "UNGRADED-K1", 0), k2 = point(book, "UNGRADED-K2", 1);
        String drawn = question(k1, "1");
        question(k1, "2");
        question(k2, "1");
        question(k2, "2");
        String learner = learner("random-ungraded-" + status);
        attempt(learner, k1, drawn, status, null, "random", PracticeDrawReason.OLDEST, TODAY);

        Set<String> drawnPoints = new HashSet<>();
        for (int round = 0; round < 40; round++) {
            var selection = selector.select(learner, Set.of(k1, k2), Set.of()).orElseThrow();
            // 当天 quota 已经被占用：这道题今天不能再出。
            assertThat(selection.questionId()).isNotEqualTo(drawn);
            assertThat(selection.requestedPool()).isNull();
            drawnPoints.add(selection.targetKnowledgePointId());
        }
        // 没有 grading 结果时不偏向任何 KP，仍然是在 eligible 集合里纯随机。
        assertThat(drawnPoints).containsExactlyInAnyOrder(k1, k2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_random_kp_rotation WHERE learner_id=?",
                Integer.class, learner)).isZero();
    }

    // ---------------------------------------------------------------- wrong lane

    /**
     * 多 KnowledgePoint Question：错题记录是在另一个 KP 下产生的（target=K2），
     * 但 Q 本身属于 K1，因此仍然是 K1 wrong lane 的候选。
     */
    @Test void wrongLaneAcceptsActiveWrongQuestionBelongingToThePointByRelation() {
        String book = book("RANDOM-MULTI-KP-WRONG");
        String k1 = point(book, "MULTI-K1", 0), k2 = point(book, "MULTI-K2", 1);
        String shared = question(k1, "1");
        relate(shared, k2, "auxiliary", 1);
        String pad = question(k1, "2");
        String learner = learner("random-multi-kp-wrong");
        // 错题本记录来自 K2 context。
        wrongBook(learner, k2, shared, "active");
        // 一道昨天的 RANDOM attempt 让 K1 进入 wrong lane（奇数）。
        attempt(learner, k1, pad, "graded", "wrong", "random", PracticeDrawReason.OLDEST, YESTERDAY);

        // “属于当前 KP”由 question_resource_knowledge 决定；“是否 active 错题”只看 learner + question。
        assertThat(selections.activeWrongQuestionIds(learner, List.of(shared))).containsExactly(shared);

        var selection = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(selection.targetKnowledgePointId()).isEqualTo(k1);
        assertThat(selection.drawReason()).isEqualTo(PracticeDrawReason.WRONG);
        assertThat(selection.questionId()).isEqualTo(shared);

        // 手动移出后，任何 KP 的 wrong lane 都不再包含它。
        jdbc.update("UPDATE learner_wrong_question SET status='removed' WHERE learner_id=? AND question_id=?",
                learner, shared);
        assertThat(selections.activeWrongQuestionIds(learner, List.of(shared))).isEmpty();
        var fallback = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(fallback.drawReason()).isEqualTo(PracticeDrawReason.WRONG_FALLBACK);
    }

    // ---------------------------------------------------------------- oldest lane

    @Test void oldestLanePrefersNeverGradedQuestions() {
        String book = book("RANDOM-OLDEST");
        String k1 = point(book, "OLDEST-K1", 0);
        String never = question(k1, "1"), older = question(k1, "2");
        String learner = learner("random-oldest-never");
        graded(learner, k1, older, "correct", TEN_DAYS_AGO);

        var selection = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(selection.questionId()).isEqualTo(never);
        assertThat(selection.drawReason()).isEqualTo(PracticeDrawReason.OLDEST);
    }

    @Test void oldestLaneThenPrefersTheEarliestLastGradedQuestion() {
        String book = book("RANDOM-EARLIEST");
        String k1 = point(book, "EARLIEST-K1", 0);
        String earliest = question(k1, "1"), middle = question(k1, "2"), recent = question(k1, "3");
        String pad = question(k1, "4");
        String learner = learner("random-oldest-earliest");
        graded(learner, k1, earliest, "wrong", TEN_DAYS_AGO);
        graded(learner, k1, middle, "correct", FIVE_DAYS_AGO);
        graded(learner, k1, recent, "wrong", ONE_DAY_AGO);
        // 两道“昨天”的 RANDOM attempt 把 lane 保持在 oldest（偶数），同时不占今天的额度。
        attempt(learner, k1, pad, "graded", "wrong", "random", PracticeDrawReason.OLDEST, YESTERDAY);
        attempt(learner, k1, pad, "graded", "wrong", "random", PracticeDrawReason.OLDEST, YESTERDAY);

        assertThat(selector.select(learner, Set.of(k1), Set.of()).orElseThrow().questionId()).isEqualTo(earliest);
    }

    @Test void oldestLaneReadsLastGradedAcrossEveryFormalDrawMode() {
        String book = book("RANDOM-CROSS-MODE");
        String k1 = point(book, "CROSS-K1", 0);
        String chapterQuestion = question(k1, "1"), knowledgeQuestion = question(k1, "2");
        String learner = learner("random-cross-mode");
        // 只有 chapter / knowledge 历史时，oldest 也必须认为这两道题“最近做过”。
        attempt(learner, k1, chapterQuestion, "graded", "correct", "chapter", null, ONE_DAY_AGO);
        attempt(learner, k1, knowledgeQuestion, "graded", "correct", "knowledge", null, THREE_DAYS_AGO);

        assertThat(selector.select(learner, Set.of(k1), Set.of()).orElseThrow().questionId())
                .isEqualTo(knowledgeQuestion);
    }

    // ---------------------------------------------------------------- wrong lane

    @Test void wrongLaneOnlyUsesActivePermanentWrongBookEntries() {
        String book = book("RANDOM-WRONG-LANE");
        String k1 = point(book, "LANE-K1", 0);
        String wrongQuestion = question(k1, "1"), otherQuestion = question(k1, "2");
        String learner = learner("random-wrong-lane");
        // 一道昨天的 RANDOM attempt 让 lane 进入奇数（wrong lane）。
        attempt(learner, k1, otherQuestion, "graded", "wrong", "random", PracticeDrawReason.OLDEST, YESTERDAY);
        wrongBook(learner, k1, wrongQuestion, "active");

        var selection = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(selection.drawReason()).isEqualTo(PracticeDrawReason.WRONG);
        assertThat(selection.questionId()).isEqualTo(wrongQuestion);
    }

    @Test void removedWrongQuestionLeavesTheWrongLaneAndFallsBackToOldest() {
        String book = book("RANDOM-WRONG-FALLBACK");
        String k1 = point(book, "FALLBACK-K1", 0);
        String removedFromBook = question(k1, "1"), otherQuestion = question(k1, "2");
        String learner = learner("random-wrong-fallback");
        attempt(learner, k1, otherQuestion, "graded", "wrong", "random", PracticeDrawReason.OLDEST, YESTERDAY);
        // 手动移出错题本后立即不再是 wrong lane 候选。
        wrongBook(learner, k1, removedFromBook, "removed");
        graded(learner, k1, otherQuestion, "wrong", THREE_DAYS_AGO);

        var selection = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(selection.drawReason()).isEqualTo(PracticeDrawReason.WRONG_FALLBACK);
        // fallback 走 oldest：removedFromBook 从未 graded，最优先。
        assertThat(selection.questionId()).isEqualTo(removedFromBook);
    }

    @Test void wrongFallbackStillConsumesTheLaneSlotSoTheNextDrawIsOldest() {
        String book = book("RANDOM-LANE-SLOT");
        String k1 = point(book, "SLOT-K1", 0);
        String removedFromBook = question(k1, "1"), otherQuestion = question(k1, "2");
        String learner = learner("random-lane-slot");
        attempt(learner, k1, otherQuestion, "graded", "wrong", "random", PracticeDrawReason.OLDEST, YESTERDAY);
        wrongBook(learner, k1, removedFromBook, "removed");
        graded(learner, k1, otherQuestion, "wrong", THREE_DAYS_AGO);

        var fallback = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(fallback.drawReason()).isEqualTo(PracticeDrawReason.WRONG_FALLBACK);
        // 真实流程会把这次发题写成 RANDOM attempt：wrong slot 因此算消费。
        attempt(learner, k1, fallback.questionId(), "graded", "correct", "random",
                PracticeDrawReason.WRONG_FALLBACK, TODAY);

        var nextDraw = selector.select(learner, Set.of(k1), Set.of()).orElseThrow();
        assertThat(nextDraw.drawReason()).isEqualTo(PracticeDrawReason.OLDEST);
    }

    // ---------------------------------------------------------------- World 端到端

    @Test void worldRandomAttemptsRecordDrawModeAndAdvanceOneFormalSlotPerGradedAnswer() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String bookName = "世界随机进度文集";
        insertBook(book, chapter, bookName);
        String point = pointIn(book, chapter, "WORLD-RANDOM-K", 0);
        Set<String> questions = new LinkedHashSet<>();
        for (int index = 0; index < 3; index++) questions.add(questionIn(point, "WORLD-RANDOM", String.valueOf(index + 1)));
        Cookie cookie = register("random-world-progress");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username=?", String.class, "random-world-progress");
        selectBook(learner, book);
        initialize(cookie);

        JsonNode game = begin(cookie, "read");
        assertThat(game.path("adventure").path("run").path("plannedRounds").asInt()).isEqualTo(3);
        String firstAttempt = game.path("attempt").path("id").asText();
        String firstQuestion = game.path("attempt").path("question").path("id").asText();
        assertThat(jdbc.queryForObject("SELECT draw_mode FROM study_attempt WHERE id=?", String.class, firstAttempt))
                .isEqualTo("random");
        assertThat(jdbc.queryForObject("SELECT draw_reason FROM study_attempt WHERE id=?", String.class, firstAttempt))
                .isEqualTo(PracticeDrawReason.OLDEST);
        assertThat(jdbc.queryForObject("SELECT selection_count FROM learner_random_kp_rotation WHERE learner_id=?",
                Long.class, learner)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT last_random_attempt_id FROM learner_random_attempt_cursor WHERE learner_id=?",
                String.class, learner)).isEqualTo(firstAttempt);

        // 答错也推进一个正式题 slot：不再 training、不再 retry 父题、不再建诊断会话。
        game = answer(cookie, game, false);
        JsonNode run = game.path("adventure").path("run");
        assertThat(run.path("knowledgePointIndex").asInt()).isEqualTo(1);
        assertThat(run.path("training").asBoolean()).isFalse();
        assertThat(run.path("retryQuestionId").isNull()).isTrue();
        assertThat(run.path("diagnosisSessionId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_session WHERE learner_id=?",
                Integer.class, learner)).isZero();

        game = next(cookie, game);
        String secondAttempt = game.path("attempt").path("id").asText();
        String secondQuestion = game.path("attempt").path("question").path("id").asText();
        assertThat(secondQuestion).isNotEqualTo(firstQuestion);
        // 上一题 wrong → 留在同一 KP；但该 KP 的错题今天已经出过，所以 wrong lane 空转 oldest。
        assertThat(jdbc.queryForObject("SELECT draw_reason FROM study_attempt WHERE id=?", String.class, secondAttempt))
                .isEqualTo(PracticeDrawReason.WRONG_FALLBACK);
        assertThat(jdbc.queryForObject("SELECT selection_count FROM learner_random_kp_rotation WHERE learner_id=?",
                Long.class, learner)).isEqualTo(1L);
        game = answer(cookie, game, true);
        assertThat(game.path("adventure").path("run").path("knowledgePointIndex").asInt()).isEqualTo(2);

        game = next(cookie, game);
        String thirdQuestion = game.path("attempt").path("question").path("id").asText();
        String thirdAttempt = game.path("attempt").path("id").asText();
        assertThat(Set.of(firstQuestion, secondQuestion, thirdQuestion)).isEqualTo(questions);
        assertThat(jdbc.queryForObject("SELECT last_requested_pool FROM learner_random_kp_rotation WHERE learner_id=?",
                String.class, learner)).isEqualTo("wrong");
        assertThat(jdbc.queryForObject("SELECT selection_count FROM learner_random_kp_rotation WHERE learner_id=?",
                Long.class, learner)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT last_random_attempt_id FROM learner_random_attempt_cursor WHERE learner_id=?",
                String.class, learner)).isEqualTo(thirdAttempt);
        game = answer(cookie, game, true);

        run = game.path("adventure").path("run");
        assertThat(run.path("status").asText()).isEqualTo("settled");
        assertThat(run.path("knowledgePointIndex").asInt()).isEqualTo(3);
        assertThat(run.path("score").asInt()).isEqualTo(67);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(DISTINCT question_id) FROM study_attempt
                 WHERE learner_id=? AND draw_mode='random'
                """, Integer.class, learner)).isEqualTo(3);
    }

    @Test void worldActivityExplainsExhaustedDailyQuotaInsteadOfStartingAndCrashing() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "世界额度耗尽文集");
        String point = pointIn(book, chapter, "WORLD-EXHAUSTED-K", 0);
        String question = questionIn(point, "WORLD-EXHAUSTED", "1");
        Cookie cookie = register("random-world-exhausted");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username=?", String.class, "random-world-exhausted");
        selectBook(learner, book);
        initialize(cookie);
        // 当天这个范围里的正式题已经全部 RANDOM 出过（发题即占额度，未判题也算）。
        jdbc.update("""
                INSERT INTO study_attempt(id,learner_id,world_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,target_knowledge_point_id,evidence_mode,question_difficulty,
                    draw_mode,draw_reason,created_at)
                VALUES (?,?,'ancient-official',?,'{}','true','active','auto',?,'normal',2,'random','oldest',?)
                """, UUID.randomUUID().toString(), learner, question, point, java.sql.Timestamp.from(TODAY));
        assertThat(selector.remainingToday(learner, Set.of(point), Set.of())).isZero();

        mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("今天学习范围内的随机题已经全部出过了，明天再来吧。"));
    }

    /**
     * 发题后不作答、直接放弃本轮：该 Question 当天 quota 已占用，
     * 新 run 不得重复它，也不得因为这次未作答而产生任何错题记录。
     */
    @Test void worldAbandonedUngradedRandomAttemptStillConsumesTheDailyQuota() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "世界放弃回归文集");
        String point = pointIn(book, chapter, "WORLD-ABANDON-K", 0);
        String first = questionIn(point, "WORLD-ABANDON", "1");
        String second = questionIn(point, "WORLD-ABANDON", "2");
        Cookie cookie = register("random-world-abandon");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username=?", String.class, "random-world-abandon");
        selectBook(learner, book);
        initialize(cookie);

        JsonNode game = begin(cookie, "read");
        assertThat(game.path("adventure").path("run").path("plannedRounds").asInt()).isEqualTo(2);
        String abandonedAttempt = game.path("attempt").path("id").asText();
        String abandonedQuestion = game.path("attempt").path("question").path("id").asText();

        // 发题后完全不作答，直接放弃本轮。
        abandon(cookie, game);
        assertThat(jdbc.queryForObject("SELECT status FROM study_attempt WHERE id=?", String.class, abandonedAttempt))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject("SELECT draw_mode FROM study_attempt WHERE id=?", String.class,
                abandonedAttempt)).isEqualTo("random");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_wrong_question WHERE learner_id=?",
                Integer.class, learner)).isZero();

        // 新 run：这道题当天已经发出过，不能重复；只剩一道可出，plannedRounds 收敛为 1。
        JsonNode restarted = begin(cookie, "read");
        assertThat(restarted.path("adventure").path("run").path("plannedRounds").asInt()).isEqualTo(1);
        String restartedAttempt = restarted.path("attempt").path("id").asText();
        String nextQuestion = restarted.path("attempt").path("question").path("id").asText();
        assertThat(nextQuestion).isNotEqualTo(abandonedQuestion);
        assertThat(Set.of(abandonedQuestion, nextQuestion)).isEqualTo(Set.of(first, second));
        // 上一题没有 assessment，K1 的 lane 仍然按“奇数 = wrong lane”推进，只是 wrong lane 没有候选。
        assertThat(jdbc.queryForObject("SELECT draw_reason FROM study_attempt WHERE id=?", String.class,
                restartedAttempt)).isEqualTo(PracticeDrawReason.WRONG_FALLBACK);
    }

    // ---------------------------------------------------------------- helpers

    private void initialize(Cookie cookie) throws Exception {
        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode begin(Cookie cookie, String activity) throws Exception {
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"%s\"}".formatted(activity)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode answer(Cookie cookie, JsonNode game, boolean correct) throws Exception {
        String attempt = game.path("attempt").path("id").asText();
        String question = game.path("attempt").path("question").path("id").asText();
        boolean standard = Boolean.parseBoolean(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, attempt));
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/answers").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attempt, question, correct == standard)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode next(Cookie cookie, JsonNode game) throws Exception {
        String attempt = game.path("attempt").path("id").asText();
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/next").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\"}".formatted(attempt)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void abandon(Cookie cookie, JsonNode game) throws Exception {
        String runId = game.path("adventure").path("run").path("id").asText();
        mvc.perform(post("/api/v1/worlds/ancient-official/activities/abandon").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"runId\":\"%s\"}".formatted(runId)))
                .andExpect(status().isOk());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"随机\",\"password\":\"password-123\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private void selectBook(String learner, String book) {
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
    }

    private String learner(String username) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO learner_account(id,username,display_name,password_hash,status,revision)
                VALUES (?,?,?,'x','active',1)
                """, id, username, username);
        return id;
    }

    private String book(String name) {
        String id = UUID.randomUUID().toString();
        insertBook(id, UUID.randomUUID().toString(), name);
        return id;
    }

    private void insertBook(String book, String chapter, String name) {
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,'',TRUE,1,1)", book, name);
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,'C','章','',0,1)
                """, chapter, book);
    }

    private String point(String book, String label, int order) {
        String chapter = jdbc.queryForObject(
                "SELECT id FROM question_bank_chapter WHERE bank_id=? ORDER BY sort_order LIMIT 1", String.class, book);
        return pointIn(book, chapter, label, order);
    }

    private String pointIn(String book, String chapter, String label, int order) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',?,1)
                """, id, label + "-" + id, label, order);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",
                book, id, chapter, order);
        return id;
    }

    private String question(String point, String number) {
        return questionIn(point, "RANDOM-" + point, number);
    }

    private String questionIn(String point, String contentPrefix, String number) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,
                    question_number,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',2,'published',?,1)
                """, id, contentPrefix + "-" + number, number);
        QuestionFixtures.trueFalseOptions(jdbc, id);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, point);
        return id;
    }

    private void relate(String question, String point, String role, int order) {
        jdbc.update("""
                INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order)
                VALUES (?,?,?,?)
                """, question, point, role, order);
    }

    /** 直接写一条历史正式 Attempt（默认没有 draw_mode，因此不占 RANDOM 额度）。 */
    private void graded(String learner, String point, String question, String assessment, Instant answeredAt) {
        attempt(learner, point, question, "graded", assessment, null, null, answeredAt);
    }

    private void attempt(String learner, String point, String question, String status, String assessment,
                         String drawMode, String drawReason, Instant createdAt) {
        attempt(UUID.randomUUID().toString(), learner, point, question, status, assessment,
                drawMode, drawReason, createdAt);
    }

    private void attempt(String attemptId, String learner, String point, String question,
                         String status, String assessment, String drawMode, String drawReason,
                         Instant createdAt) {
        jdbc.update("""
                INSERT INTO study_attempt(id,learner_id,world_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,
                    question_difficulty,answered_at,draw_mode,draw_reason,created_at)
                VALUES (?,?,'ancient-official',?,'{}','true',?,'auto',?,?,?,'normal',2,?,?,?,?)
                """, attemptId, learner, question, status,
                "graded".equals(status) ? ("self".equals(assessment) ? "self" : "automatic") : null,
                assessment, point,
                "graded".equals(status) ? java.sql.Timestamp.from(createdAt) : null,
                drawMode, drawReason, java.sql.Timestamp.from(createdAt));
    }

    private void wrongBook(String learner, String point, String question, String status) {
        jdbc.update("""
                INSERT INTO learner_wrong_question(learner_id,question_id,target_knowledge_point_id,
                    first_wrong_at,last_wrong_at,status,removed_at)
                VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                """, learner, question, point, status, "removed".equals(status) ? java.sql.Timestamp.from(TODAY) : null);
    }
}

package cn.tihaishitu;

import cn.tihaishitu.catalog.QuestionDto;
import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.learning.LearnerKnowledgeStateService;
import cn.tihaishitu.learning.LearnerQuestionMasteryStore;
import cn.tihaishitu.game.QuestionAttemptStore;
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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Book-level 正式题池与 World 活动轮次的保留能力测试。
 *
 * <p>PR3 之后现代 Learner World 的 RANDOM 正式题不再直接对 Book 题池做 uniform random，
 * 而是先按 {@code RandomPracticeSelector} 选 target KnowledgePoint、再在该 KP 内按
 * oldest / wrong lane 选题；因此本类前三个测试覆盖的是**仍然保留的 store / service 能力**
 * （Book 题池口径、稳定 target 解析），不再是 World 的发题路径。</p>
 *
 * <pre>
 * selected Book(s)
 *   → 全部 active KnowledgePoint
 *   → 与之有关系的 published Formal Parent Question（core + auxiliary 都算）
 *   → 按 question_id DISTINCT 去重
 *   → 排除本 run seenQuestionIds
 * </pre>
 *
 * 仍然不会因为“知识点数量少于 rounds”阻止副本开始。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:world-book-pool;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class WorldBookPoolIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired KnowledgeQuestionPoolService pool;
    @Autowired LearnerKnowledgeStateService knowledgeStates;
    @Autowired LearnerQuestionMasteryStore mastery;

    @Test
    void bookPoolCollectsEveryDistinctFormalQuestionAcrossKnowledgePoints() {
        String learner = learner("book-pool-scope");
        String bookA = book("题池文集 A");
        String bookB = book("题池文集 B");
        String k1 = point("K1", 0), k2 = point("K2", 1);
        bookPoint(bookA, k1, 0); bookPoint(bookA, k2, 1);
        selectBook(learner, bookA);

        String q1 = question(k1, "core", null);
        String q2 = question(k1, "core", null);
        String q3 = question(k1, "core", null);
        String q4 = question(k2, "core", null);
        String q5 = question(k2, "core", null);
        String q6 = question(k2, "core", null);

        // 2 个知识点、6 道题：候选就是全部 6 道去重题，不按知识点分组。
        assertThat(ids(pool.candidatesForBooks(Set.of(bookA), Set.of())))
                .containsExactlyInAnyOrder(q1, q2, q3, q4, q5, q6);

        // run 内 seen 排除。
        assertThat(ids(pool.candidatesForBooks(Set.of(bookA), Set.of(q1, q2))))
                .containsExactlyInAnyOrder(q3, q4, q5, q6);

        // auxiliary 关系同样让题目进入 Book 题池。
        String k3 = point("K3", 2);
        bookPoint(bookB, k3, 0);
        String auxiliaryOnly = question(k3, "auxiliary", null);
        assertThat(ids(pool.candidatesForBooks(Set.of(bookA), Set.of()))).doesNotContain(auxiliaryOnly);
        assertThat(ids(pool.candidatesForBooks(Set.of(bookA, bookB), Set.of()))).contains(auxiliaryOnly);

        // 同一道题同时属于两本 selected Book 时只出现一次。
        String k4 = point("K4", 3);
        bookPoint(bookB, k4, 1);
        String shared = question(k3, "core", k4);
        List<String> both = ids(pool.candidatesForBooks(Set.of(bookA, bookB), Set.of()));
        assertThat(both.stream().filter(id -> id.equals(shared)).count()).isEqualTo(1);
    }

    @Test
    void targetKnowledgePointIsStableCoreThenAuxiliary() {
        String book = book("目标文集");
        String coreFirst = point("CORE-1", 0), coreSecond = point("CORE-2", 1), auxiliary = point("AUX", 2);
        bookPoint(book, coreFirst, 0); bookPoint(book, coreSecond, 1); bookPoint(book, auxiliary, 2);
        // sort_order: core2=0, core1=1 → 稳定取 sort_order 最小的 core2。
        String question = question(coreFirst, "core", null, 1);
        relate(question, coreSecond, "core", 0);
        relate(question, auxiliary, "auxiliary", 2);

        Set<String> scope = Set.of(coreFirst, coreSecond, auxiliary);
        assertThat(pool.targetKnowledgePointFor(question, scope)).contains(coreSecond);
        for (int index = 0; index < 5; index++) {
            assertThat(pool.targetKnowledgePointFor(question, scope)).contains(coreSecond);
        }

        // 只有 auxiliary 时退回 sort_order 最小的 auxiliary。
        String auxiliaryOnly = question(auxiliary, "auxiliary", null);
        assertThat(pool.targetKnowledgePointFor(auxiliaryOnly, scope)).contains(auxiliary);

        // 完全不在 scope 内的题退回该题任意 active 关联知识点。
        assertThat(pool.targetKnowledgePointFor(auxiliaryOnly, Set.of(coreFirst))).contains(auxiliary);
    }

    @Test
    void auxiliaryOnlyRelationStillPutsTheQuestionInTheBookPool() {
        String learner = learner("aux-only-pool");
        String book = book("辅助覆盖文集");
        String auxiliary = point("AUX-ONLY", 0);
        bookPoint(book, auxiliary, 0);
        String question = question(auxiliary, "auxiliary", null);
        selectBook(learner, book);
        assertThat(ids(pool.candidatesForBooks(Set.of(book), Set.of()))).containsExactly(question);
    }

    @Test
    void twoKnowledgePointsAndFiveRoundsStillStartTheWorldActivity() throws Exception {
        Cookie cookie = register("world-rounds");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='world-rounds'", String.class);
        String book = book("轮次文集");
        String k1 = point("ROUND-K1", 0), k2 = point("ROUND-K2", 1);
        bookPoint(book, k1, 0); bookPoint(book, k2, 1);
        for (int index = 0; index < 3; index++) question(k1, "core", null);
        for (int index = 0; index < 3; index++) question(k2, "core", null);
        selectBook(learner, book);

        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());

        // 只有 2 个 KnowledgePoint，但 read 需要 5 轮：必须能开始，不再报“可挑战知识点不足”。
        String body = mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adventure.run.plannedRounds").value(5))
                .andReturn().getResponse().getContentAsString();
        var run = mapper.readTree(body).path("adventure").path("run");
        assertThat(run.path("status").asText()).isEqualTo("active");
        // 不再预选 KnowledgePoint：knowledgePointIds 为空，题干仍来自整书题池。
        assertThat(run.path("knowledgePointIds")).isEmpty();
        String attempt = mapper.readTree(body).path("attempt").path("id").asText();
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM study_attempt WHERE id=?",
                String.class, attempt)).isIn(k1, k2);
    }

    @Test
    void worldRoundOnlyReinforcesTheTargetKnowledgePoint() throws Exception {
        Cookie cookie = register("world-fanout");
        String learnerId = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='world-fanout'", String.class);
        String book = book("fan-out 文集");
        String core = point("FANOUT-CORE", 0), auxiliary = point("FANOUT-AUX", 1);
        bookPoint(book, core, 0); bookPoint(book, auxiliary, 1);
        String question = question(core, "core", null);
        relate(question, auxiliary, "auxiliary", 1);
        selectBook(learnerId, book);

        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var attempt = mapper.readTree(body).path("attempt");
        assertThat(attempt.path("question").path("id").asText()).isEqualTo(question);
        // RANDOM 是 KP-first：一题同时关联 core 与 auxiliary 时，两个 KP 都是合法 target，
        // 关键长期规则是“一次 attempt 只给冻结的那一个 target 记账”，不会同时加两个 KP 的分。
        String target = attempt.path("targetKnowledgePointId").asText();
        assertThat(target).isIn(core, auxiliary);
        String untouched = core.equals(target) ? auxiliary : core;

        // 一次作答只强化 target：另一个关联知识点不因为同一次 attempt 自动加分。
        String attemptId = attempt.path("id").asText();
        String frozen = answerJson(attemptId);
        assertThat(attempt.path("question").path("options").path(Boolean.parseBoolean(frozen) ? "true" : "false").asText())
                .isEqualTo("正确");
        mvc.perform(post("/api/v1/worlds/ancient-official/answers").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attemptId, question, answerJson(attemptId))))
                .andExpect(status().isOk());

        assertThat(mastery.projection(learnerId, target, Instant.now()).masteryScore()).isGreaterThan(0);
        assertThat(mastery.projection(learnerId, untouched, Instant.now()).masteryScore()).isZero();
    }

    @Test
    void worldNoIdeaGradesWrongAndAdvancesOneFormalSlot() throws Exception {
        Cookie cookie = register("world-no-idea");
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='world-no-idea'", String.class);
        String book = book("无思路文集"); String point = point("NO-IDEA", 0); bookPoint(book, point, 0);
        String question = question(point, "core", null); selectBook(learner, book);
        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());
        JsonNode game = mapper.readTree(mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String attempt = game.path("attempt").path("id").asText();
        game = mapper.readTree(mvc.perform(post("/api/v1/worlds/ancient-official/answers/no-idea").with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"attemptId\":\"%s\",\"questionId\":\"%s\"}".formatted(attempt, question)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(game.path("attempt").path("result").path("noIdea").asBoolean()).isTrue();
        assertThat(game.path("adventure").path("run").path("knowledgePointIndex").asInt()).isOne();
        assertThat(game.path("adventure").path("run").path("status").asText()).isEqualTo("settled");
        assertThat(jdbc.queryForObject("SELECT submitted_answer_json FROM answer_record WHERE attempt_id=?", String.class, attempt)).isEqualTo("null");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_wrong_question WHERE last_wrong_attempt_id=?", Integer.class, attempt)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_session WHERE root_attempt_id=?", Integer.class, attempt)).isZero();
    }

    @Test
    void worldRunEndsCleanlyWhenTheBookHasFewerQuestionsThanRounds() throws Exception {
        Cookie cookie = register("world-short");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='world-short'", String.class);
        String book = book("小题量文集");
        String point = point("SHORT-K1", 0);
        bookPoint(book, point, 0);
        question(point, "core", null);
        selectBook(learner, book);

        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 只有 1 道题时 plannedRounds 收敛为 1，而不是硬凑 5 道重复题。
        assertThat(mapper.readTree(body).path("adventure").path("run").path("plannedRounds").asInt()).isEqualTo(1);

        String attemptId = mapper.readTree(body).path("attempt").path("id").asText();
        String questionId = mapper.readTree(body).path("attempt").path("question").path("id").asText();
        String answered = mvc.perform(post("/api/v1/worlds/ancient-official/answers").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attemptId, questionId, answerJson(attemptId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 做完最后一道自然完成本轮，而不是 500 / 无限重复。
        assertThat(mapper.readTree(answered).path("adventure").path("run").path("status").asText()).isEqualTo("settled");
    }

    @Test
    void worldActivityFailsFastWhenTheBookHasNoFormalQuestion() throws Exception {
        Cookie cookie = register("world-empty");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='world-empty'", String.class);
        String book = book("空题池文集");
        String point = point("EMPTY-K1", 0);
        bookPoint(book, point, 0);
        // 知识点 active 但没有任何 published 正式父题。
        selectBook(learner, book);
        assertThat(pool.candidatesForBooks(Set.of(book), Set.of())).isEmpty();

        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());
        // 必须在开始活动阶段就明确失败，而不是 plannedRounds=1 之后到发题时才晚一步报错。
        mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("当前学习范围内没有可用的正式题。"));
    }

    @Test
    void worldPlannedRoundsShrinksToThreeWhenOnlyThreeQuestionsExist() throws Exception {
        Cookie cookie = register("world-three");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='world-three'", String.class);
        String book = book("三题文集");
        String point = point("THREE-K1", 0);
        bookPoint(book, point, 0);
        for (int index = 0; index < 3; index++) question(point, "core", null);
        selectBook(learner, book);

        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).path("adventure").path("run").path("plannedRounds").asInt()).isEqualTo(3);

        String current = body;
        for (int round = 1; round <= 3; round++) {
            JsonNode attempt = mapper.readTree(current).path("attempt");
            current = mvc.perform(post("/api/v1/worlds/ancient-official/answers").with(csrf()).cookie(cookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                    .formatted(attempt.path("id").asText(),
                                            attempt.path("question").path("id").asText(),
                                            answerJson(attempt.path("id").asText()))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode run = mapper.readTree(current).path("adventure").path("run");
            if (round < 3) {
                assertThat(run.path("status").asText()).isEqualTo("active");
                current = mvc.perform(post("/api/v1/worlds/ancient-official/next").with(csrf()).cookie(cookie)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"attemptId\":\"%s\"}".formatted(attempt.path("id").asText())))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            } else {
                assertThat(run.path("status").asText()).isEqualTo("settled");
                assertThat(run.path("score").asInt()).isEqualTo(100);
            }
        }
    }

    private List<String> ids(List<QuestionDto> questions) {
        return questions.stream().map(QuestionDto::id).toList();
    }

    private String learner(String username) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO learner_account(id,username,display_name,password_hash,status,revision)
                VALUES (?,?,?,'x','active',1)
                """, id, username, username);
        return id;
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"世界题池\",\"password\":\"password-123\"}"
                                .formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private void selectBook(String learner, String book) {
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learner, book);
    }

    private String book(String name) {
        String id = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,'',TRUE,1,1)",
                id, name);
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,'C','章','',0,1)
                """, chapter, id);
        return id;
    }

    private String point(String name, int order) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','节','章','core','active','','',?,1)
                """, id, "POOL-" + name + "-" + id, name, order);
        return id;
    }

    private void bookPoint(String book, String point, int order) {
        String chapter = jdbc.queryForObject(
                "SELECT id FROM question_bank_chapter WHERE bank_id=? ORDER BY sort_order LIMIT 1",
                String.class, book);
        jdbc.update("""
                INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order)
                VALUES (?,?,?,?)
                """, book, point, chapter, order);
    }

    private String question(String corePoint, String coreRole, String extraPoint) {
        return question(corePoint, coreRole, extraPoint, 0);
    }

    private String question(String corePoint, String coreRole, String extraPoint, int coreSortOrder) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto',?,'true','解析',2,'published',1)
                """, id, "题池题-" + id);
        jdbc.update("INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, "true", "正确", true, 0);
        jdbc.update("INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, "false", "错误", false, 1);
        relate(id, corePoint, coreRole, coreSortOrder);
        if (extraPoint != null) relate(id, extraPoint, "core", 1);
        return id;
    }

    private void relate(String question, String point, String role, int order) {
        jdbc.update("""
                INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order)
                VALUES (?,?,?,?)
                """, question, point, role, order);
    }

    private String answerJson(String attemptId) {
        return jdbc.queryForObject("SELECT standard_answer_json FROM study_attempt WHERE id=?",
                String.class, attemptId);
    }
}

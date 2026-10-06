package cn.tihaishitu.learning;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.learner.LearnerAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Map;
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
 * Study 章节练习入口的“可练知识点数”等于章节内存在正式题的知识点数：
 * 不再随每日答题情况、Review 到期或依赖 readiness 变化（最新长期规则）。
 * 同时 /learner/progress 的最近学习路径必须来自正式目录（Book → Chapter），
 * 不得再回落到 legacy subject_name / section_name / chapter_name。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-chapter-availability;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerChapterAvailabilityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired LearnerPracticeService practice;
    @Autowired KnowledgeQuestionPoolService pool;
    @Autowired LearnerPracticeStore store;
    @Autowired QuestionAttemptStore attempts;
    @Autowired LearnerKnowledgeStateService knowledgeStates;

    @Test
    void chapterAvailabilityTracksLearnerStateAndProgressUsesCatalogChapterName() throws Exception {
        Cookie cookie = register("chapter-availability");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='chapter-availability'", String.class);
        Fixture fixture = fixture(learner);

        // 目录静态计数为 2：两个知识点都挂着已发布正式题。
        // 整本一次请求即可拿到两章各自的可练知识点数。
        mvc.perform(get("/api/v1/learning/books/{id}", fixture.book).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].knowledgePointCount").value(2))
                .andExpect(jsonPath("$.chapters[0].trainableKnowledgePointCount").value(2))
                .andExpect(jsonPath("$.chapters[0].availableKnowledgePointCount").value(2))
                .andExpect(jsonPath("$.chapters[1].availableKnowledgePointCount").value(1));
        assertThat(practice.availableChapterKnowledgePointCounts(learner, fixture.book,
                List.of(fixture.chapter, fixture.secondChapter)))
                .containsEntry(fixture.chapter, 2)
                .containsEntry(fixture.secondChapter, 1);
        assertBatchMatchesSingleChapter(learner, fixture);

        // 今天答对第一题后仍然可以继续练：可练知识点数不下降，也不会因 Review 未到期变成 0。
        graded(learner, fixture.firstPoint, fixture.firstQuestion, true, Instant.now());
        assertThat(practice.availableChapterKnowledgePointCount(learner, fixture.book, fixture.chapter)).isEqualTo(2);
        mvc.perform(get("/api/v1/learning/books/{id}", fixture.book).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].availableKnowledgePointCount").value(2))
                .andExpect(jsonPath("$.chapters[1].availableKnowledgePointCount").value(1));
        assertBatchMatchesSingleChapter(learner, fixture);

        // 两道题都答对后依然可练：Playability 与 Mastery 每日奖励已经完全分离。
        graded(learner, fixture.secondPoint, fixture.secondQuestion, true, Instant.now());
        assertThat(practice.availableChapterKnowledgePointCount(learner, fixture.book, fixture.chapter)).isEqualTo(2);
        mvc.perform(get("/api/v1/learning/books/{id}", fixture.book).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].availableKnowledgePointCount").value(2));
        assertBatchMatchesSingleChapter(learner, fixture);

        // 章节练习仍然可以正常启动，并在 Session 内不重复同一道题。
        JsonNode session = json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType("application/json")
                        .content("{\"intent\":\"chapter_drill\",\"targetBookId\":\"%s\",\"targetChapterId\":\"%s\"}"
                                .formatted(fixture.book, fixture.chapter)))
                .andExpect(status().isCreated()).andReturn());
        String sessionId = session.path("id").asText();
        String firstSeen = session.path("currentAttempt").path("question").path("id").asText();
        assertThat(firstSeen).isIn(fixture.firstQuestion, fixture.secondQuestion);

        // 第二个业务日不会改变可练性。
        jdbc.update("""
                UPDATE study_attempt SET answered_at = TIMESTAMPADD('DAY', -1, answered_at)
                 WHERE learner_id=? AND target_knowledge_point_id IN (?,?)
                """, learner, fixture.firstPoint, fixture.secondPoint);
        assertThat(practice.availableChapterKnowledgePointCount(learner, fixture.book, fixture.chapter)).isEqualTo(2);
        assertBatchMatchesSingleChapter(learner, fixture);

        // 进度最近学习路径必须来自正式目录 Book → Chapter，不得回落 legacy subject/section/chapter_name。
        mvc.perform(get("/api/v1/learner/progress").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recent.knowledgePoints[0].bookName").value("章节可用性文集"))
                .andExpect(jsonPath("$.recent.knowledgePoints[0].chapterName").value(fixture.chapterName))
                .andExpect(jsonPath("$.recent.knowledgePoints[0].subject").doesNotExist())
                .andExpect(jsonPath("$.recent.knowledgePoints[0].section").doesNotExist())
                .andExpect(jsonPath("$.recent.knowledgePoints[0].chapter").doesNotExist());
    }

    @Test
    void leavingTheLearningScopeMarksWrongQuestionOutOfScope() throws Exception {
        Cookie cookie = register("scope-user");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='scope-user'", String.class);
        Fixture fixture = fixture(learner);
        graded(learner, fixture.firstPoint, fixture.firstQuestion, false, Instant.now());

        mvc.perform(get("/api/v1/learner/wrong-questions").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(true))
                .andExpect(jsonPath("$[0].unavailableReason").doesNotExist());

        // 移出学习范围后，错题卡应提前变为不可练并给出 out_of_scope；错题记录本身永久保留。
        String otherBook = book();
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learner, otherBook);
        mvc.perform(get("/api/v1/learner/wrong-questions").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[0].unavailableReason").value("out_of_scope"))
                .andExpect(jsonPath("$[0].questionId").value(fixture.firstQuestion));
        mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType("application/json")
                        .content("{\"intent\":\"wrong_review\",\"sourceQuestionId\":\"%s\"}"
                                .formatted(fixture.firstQuestion)))
                .andExpect(status().isBadRequest());
    }

    /** 只保留本测试自建文集，避免注册时默认选中全部启用文集导致范围不确定。 */
    private Fixture fixture(String learner) {
        String book = book();
        String chapter = UUID.randomUUID().toString();
        String chapterName = "多元函数微分学";
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,'M1-H05',?,'',0,1)
                """, chapter, book, chapterName);
        String firstPoint = chapterPoint(book, chapter, chapterName, "多元函数微分学基本概念", 0);
        String secondPoint = chapterPoint(book, chapter, chapterName, "多元函数微分学计算", 1);
        String firstQuestion = question(firstPoint, "概念题");
        String secondQuestion = question(secondPoint, "计算题");

        // 第二个 Chapter：用于验证整本批量计算按章分组，而不是只算一章。
        String secondChapter = UUID.randomUUID().toString();
        String secondChapterName = "多元函数积分学";
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,'M1-H06',?,'',1,1)
                """, secondChapter, book, secondChapterName);
        String thirdPoint = chapterPoint(book, secondChapter, secondChapterName, "二重积分计算", 0);
        String thirdQuestion = question(thirdPoint, "积分题");

        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learner, book);

        Set<String> allowed = pool.allowedKnowledgePointIds(new LinkedHashSet<>(List.of(book)));
        assertThat(allowed).contains(firstPoint, secondPoint, thirdPoint);
        assertThat(store.chapterKnowledgePoints(learner, book, chapter)).containsExactlyInAnyOrder(
                firstPoint, secondPoint);
        assertThat(store.chapterKnowledgePoints(learner, book, secondChapter)).containsExactly(thirdPoint);
        assertThat(store.bookChapterMemberships(learner, book)).hasSize(3);
        return new Fixture(book, chapter, chapterName, secondChapter, secondChapterName,
                firstPoint, secondPoint, thirdPoint, firstQuestion, secondQuestion, thirdQuestion);
    }

    /** 校验：整本批量结果必须与逐章单章计算完全一致（N+1 优化不改变语义）。 */
    private void assertBatchMatchesSingleChapter(String learner, Fixture fixture) {
        Map<String, Integer> batch = practice.availableChapterKnowledgePointCounts(
                learner, fixture.book, List.of(fixture.chapter, fixture.secondChapter));
        int singleFirst = practice.availableChapterKnowledgePointCount(learner, fixture.book, fixture.chapter);
        int singleSecond = practice.availableChapterKnowledgePointCount(learner, fixture.book, fixture.secondChapter);
        assertThat(batch).containsEntry(fixture.chapter, singleFirst)
                .containsEntry(fixture.secondChapter, singleSecond);
        assertThat(batch.keySet()).containsExactlyInAnyOrder(fixture.chapter, fixture.secondChapter);
    }

    private String book() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_bank(id,name,description,enabled,weight_value,revision)
                VALUES (?,'章节可用性文集','',TRUE,1,1)
                """, id);
        return id;
    }

    private String chapterPoint(String book, String chapter, String chapterName, String name, int order) {
        String point = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','高等数学',?,'core','active','','',?,1)
                """, point, "AVAIL-" + point, name, chapterName, order);
        jdbc.update("""
                INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order)
                VALUES (?,?,?,?)
                """, book, point, chapter, order);
        return point;
    }

    private String question(String point, String content) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto',?,'true','解析',2,'published',1)
                """, id, content);
        jdbc.update("""
                INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order)
                VALUES (?,?,'core',0)
                """, id, point);
        return id;
    }

    /** 走真实判分链路（QuestionAttemptStore.recordAnswer），错题本与最近一次判分结果才会同步写入。 */
    private void graded(String learner, String point, String question, boolean correct, Instant occurredAt) {
        String attemptId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_attempt(id,learner_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,target_knowledge_point_id,evidence_mode,question_difficulty)
                VALUES (?,?,?,'{}','true','active','auto',?,'normal',2)
                """, attemptId, learner, question, point);
        var snapshot = new QuestionAttemptStore.Snapshot(attemptId, null, learner, null, null, question,
                mapper.createObjectNode(), mapper.getNodeFactory().textNode("true"),
                "active", "auto", null, null, point, "normal", 2, null, null, null);
        boolean recorded = attempts.recordAnswer(snapshot,
                mapper.getNodeFactory().textNode(correct ? "true" : "false"), correct, occurredAt);
        assertThat(recorded).isTrue();
        // 与真实判分链路一致地写入掌握度证据，进度“最近学习”才有数据。
        knowledgeStates.apply(snapshot, correct ? "correct" : "wrong", "automatic", occurredAt);
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"%s\",\"displayName\":\"章节可用性\",\"password\":\"password-123\"}"
                                .formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private JsonNode json(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private record Fixture(String book, String chapter, String chapterName, String secondChapter,
                           String secondChapterName, String firstPoint, String secondPoint, String thirdPoint,
                           String firstQuestion, String secondQuestion, String thirdQuestion) {}
}

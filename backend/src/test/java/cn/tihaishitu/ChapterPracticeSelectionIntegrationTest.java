package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.learning.ChapterPracticeSelector;
import cn.tihaishitu.learning.PracticeBusinessDay;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR3 CHAPTER 选题策略：固定确定性题序 + 跨 Session 持久 cursor + 末尾 wrap。
 *
 * <pre>
 * 1. Chapter 内 KnowledgePoint 的 question_bank_knowledge.sort_order
 * 2. 同 KP 内按稳定 Source identity 分组（优先 source_id）
 * 3. exam_year
 * 4. question_number 自然排序（1 &lt; 2 &lt; 7 &lt; 10 &lt; 22）
 * 5. question_id 稳定兜底
 * </pre>
 *
 * <p>不再“KP 顺序 + KP 内随机”；cursor 粒度是 Learner × Book × Chapter，
 * 只创建 / reveal 但未 graded 不推进，最后一题之后 wrap 到第一题。</p>
 */
@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:chapter-selection;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ChapterPracticeSelectionIntegrationTest {
    private static final Instant TODAY = PracticeBusinessDay.startOfDay(Instant.now()).plus(Duration.ofHours(12));

    @Autowired JdbcTemplate jdbc; @Autowired ChapterPracticeSelector chapters;
    @Autowired RandomPracticeSelector randomSelector; @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;

    @Test void chapterSequenceFollowsKnowledgePointOrderThenNaturalQuestionNumberThenWraps() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "章节顺序文集");
        String firstPoint = pointIn(book, chapter, "CHAPTER-ORDER-A", 0);
        String secondPoint = pointIn(book, chapter, "CHAPTER-ORDER-B", 1);
        // 题号刻意做成字符串排序会错的顺序：1 < 10 < 2。
        String a1 = question(firstPoint, "1"), a10 = question(firstPoint, "10"), a2 = question(firstPoint, "2");
        String b1 = question(secondPoint, "1");
        Cookie cookie = register("chapter-order");
        String learner = learnerId("chapter-order");
        selectBook(learner, book);

        assertThat(sequenceIds(learner, book, chapter, Set.of(firstPoint, secondPoint)))
                .containsExactly(a1, a2, a10, b1);

        // 走一遍真实 Hub 流程：KP 顺序 → 自然题号 → 末尾 wrap。
        JsonNode session = startChapter(cookie, book, chapter);
        assertThat(currentQuestion(session)).isEqualTo(a1);
        assertThat(session.path("currentKnowledgePointId").asText()).isEqualTo(firstPoint);

        for (String expected : List.of(a2, a10, b1)) {
            session = gradeCurrent(cookie, session, true);
            session = next(cookie, session.path("id").asText());
            assertThat(currentQuestion(session)).isEqualTo(expected);
        }
        session = gradeCurrent(cookie, session, true);
        session = next(cookie, session.path("id").asText());
        // 章节是连续练习模式：最后一题之后 wrap 回第一题，而不是永久 complete。
        assertThat(currentQuestion(session)).isEqualTo(a1);
        // 本 Session 的每一次发题都必须是 chapter 模式（wrap 后第一题会被第二次发出）。
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM study_attempt WHERE practice_session_id=? AND draw_mode<>'chapter'
                """, Integer.class, session.path("id").asText())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_attempt WHERE practice_session_id=?",
                Integer.class, session.path("id").asText())).isEqualTo(5);
    }

    @Test void displayNameChangeDoesNotChangeTheSequence() {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "章节来源稳定性文集");
        String point = pointIn(book, chapter, "CHAPTER-SOURCE", 0);
        String source = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_source(id,source_type,canonical_name,display_name,status,revision)
                VALUES (?,'real_exam',?,'旧展示名','active',1)
                """, source, "CHAPTER-SOURCE-" + source);
        String ten = questionWithSource(point, source, "10");
        String two = questionWithSource(point, source, "2");
        String learner = learner("chapter-source-stability");
        selectBook(learner, book);

        List<String> before = sequenceIds(learner, book, chapter, Set.of(point));
        assertThat(before).containsExactly(two, ten);
        // display_name 是可修改的展示事实，绝不能影响题序。
        jdbc.update("UPDATE question_source SET display_name='改过的展示名' WHERE id=?", source);
        assertThat(sequenceIds(learner, book, chapter, Set.of(point))).isEqualTo(before);
    }

    @Test void questionRelatedToMultipleChapterKnowledgePointsAppearsOnceWithTheFirstKnowledgePoint() {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "章节去重文集");
        String first = pointIn(book, chapter, "CHAPTER-DEDUPE-A", 0);
        String second = pointIn(book, chapter, "CHAPTER-DEDUPE-B", 1);
        String shared = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,
                    question_number,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','共享题','true','解析',2,'published','1',1)
                """, shared);
        QuestionFixtures.trueFalseOptions(jdbc, shared);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", shared, first);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',1)", shared, second);
        String learner = learner("chapter-dedupe");
        selectBook(learner, book);

        ChapterPracticeSelector.Sequence sequence = chapters.sequence(learner, book, chapter, Set.of(first, second));
        assertThat(sequence.size()).isOne();
        assertThat(sequence.steps().get(0).questionId()).isEqualTo(shared);
        // target KP = 按 KP 顺序第一次遇到它的位置。
        assertThat(sequence.steps().get(0).targetKnowledgePointId()).isEqualTo(first);
    }

    @Test void onlyGradedAttemptsAdvanceTheCursorAndLastQuestionWrapsToTheFirst() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "章节 cursor 文集");
        String point = pointIn(book, chapter, "CHAPTER-CURSOR", 0);
        String first = question(point, "1"), second = question(point, "2");
        Cookie cookie = register("chapter-cursor");
        String learner = learnerId("chapter-cursor");
        selectBook(learner, book);

        // 只创建 / reveal 但未 graded：cursor 不推进，新 Session 仍从第一题开始。
        JsonNode session = startChapter(cookie, book, chapter);
        assertThat(currentQuestion(session)).isEqualTo(first);
        end(cookie, session.path("id").asText());
        JsonNode restarted = startChapter(cookie, book, chapter);
        assertThat(currentQuestion(restarted)).isEqualTo(first);

        // graded 之后 cursor 推进：新 Session 从下一题继续。
        restarted = gradeCurrent(cookie, restarted, true);
        assertThat(restarted.path("currentAttempt").path("status").asText()).isEqualTo("graded");
        end(cookie, restarted.path("id").asText());
        JsonNode afterFirst = startChapter(cookie, book, chapter);
        assertThat(currentQuestion(afterFirst)).isEqualTo(second);

        // 最后一题 graded 之后 wrap 回第一题。
        afterFirst = gradeCurrent(cookie, afterFirst, true);
        end(cookie, afterFirst.path("id").asText());
        JsonNode wrapped = startChapter(cookie, book, chapter);
        assertThat(currentQuestion(wrapped)).isEqualTo(first);
    }

    @Test void cursorQuestionThatLeftTheChapterRestartsSafelyFromTheFirstQuestion() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "章节 cursor 失效文集");
        String point = pointIn(book, chapter, "CHAPTER-STALE", 0);
        String first = question(point, "1"), second = question(point, "2"), third = question(point, "3");
        Cookie cookie = register("chapter-stale");
        String learner = learnerId("chapter-stale");
        selectBook(learner, book);

        JsonNode session = gradeCurrent(cookie, startChapter(cookie, book, chapter), true);
        assertThat(currentQuestion(session)).isEqualTo(first);
        end(cookie, session.path("id").asText());
        // 历史 cursor 题已经解绑 / 不再属于该 Chapter：必须安全从当前题序第一题重新开始，不能 500。
        jdbc.update("DELETE FROM question_resource_knowledge WHERE question_id=?", first);
        JsonNode restarted = startChapter(cookie, book, chapter);
        assertThat(currentQuestion(restarted)).isEqualTo(second);
        assertThat(sequenceIds(learner, book, chapter, Set.of(point))).containsExactly(second, third);
    }

    @Test void randomDailyQuotaDoesNotBlockChapterPractice() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        insertBook(book, chapter, "章节与随机额度文集");
        String point = pointIn(book, chapter, "CHAPTER-QUOTA", 0);
        String first = question(point, "1"), second = question(point, "2");
        Cookie cookie = register("chapter-quota");
        String learner = learnerId("chapter-quota");
        selectBook(learner, book);

        // 两道题今天都已经 RANDOM 出过：RANDOM 额度为 0，但 Chapter 不受影响。
        for (String question : List.of(first, second)) randomAttemptToday(learner, point, question);
        assertThat(randomSelector.remainingToday(learner, Set.of(point), Set.of())).isZero();

        JsonNode session = startChapter(cookie, book, chapter);
        assertThat(currentQuestion(session)).isEqualTo(first);
        session = gradeCurrent(cookie, session, true);
        session = next(cookie, session.path("id").asText());
        assertThat(currentQuestion(session)).isEqualTo(second);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> sequenceIds(String learner, String book, String chapter, Set<String> points) {
        return chapters.sequence(learner, book, chapter, points).steps().stream()
                .map(ChapterPracticeSelector.Step::questionId).toList();
    }

    private JsonNode startChapter(Cookie cookie, String book, String chapter) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"chapter_drill\",\"targetBookId\":\"%s\",\"targetChapterId\":\"%s\"}"
                                .formatted(book, chapter)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode next(Cookie cookie, String sessionId) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", sessionId).with(csrf()).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private void end(Cookie cookie, String sessionId) throws Exception {
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/end", sessionId).with(csrf()).cookie(cookie))
                .andExpect(status().isOk());
    }

    private JsonNode gradeCurrent(Cookie cookie, JsonNode session, boolean correct) throws Exception {
        String sessionId = session.path("id").asText();
        String attemptId = session.path("currentAttempt").path("id").asText();
        String questionId = session.path("currentAttempt").path("question").path("id").asText();
        boolean standard = Boolean.parseBoolean(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, attemptId));
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers", sessionId)
                        .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attemptId, questionId, correct == standard)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String currentQuestion(JsonNode session) { return session.path("currentAttempt").path("question").path("id").asText(); }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"章节\",\"password\":\"password-123\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private String learnerId(String username) {
        return jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class, username);
    }

    private String learner(String username) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO learner_account(id,username,display_name,password_hash,status,revision)
                VALUES (?,?,?,'x','active',1)
                """, id, username, username);
        return id;
    }

    private void selectBook(String learner, String book) {
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
    }

    private void insertBook(String book, String chapter, String name) {
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,'',TRUE,1,1)", book, name);
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,'C','章','',0,1)
                """, chapter, book);
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

    private String question(String point, String number) { return questionWithSource(point, null, number); }

    private String questionWithSource(String point, String sourceId, String number) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,
                    question_number,source_id,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',2,'published',?,?,1)
                """, id, "章节题-" + number, number, sourceId);
        QuestionFixtures.trueFalseOptions(jdbc, id);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, point);
        return id;
    }

    private void randomAttemptToday(String learner, String point, String question) {
        jdbc.update("""
                INSERT INTO study_attempt(id,learner_id,world_id,question_id,question_snapshot_json,standard_answer_json,
                    status,grading_mode,grading_source,assessment,target_knowledge_point_id,evidence_mode,
                    question_difficulty,answered_at,draw_mode,draw_reason,created_at)
                VALUES (?,?,'ancient-official',?,'{}','true','graded','auto','automatic','correct',?,'normal',2,?,
                    'random','oldest',?)
                """, UUID.randomUUID().toString(), learner, question, point,
                java.sql.Timestamp.from(TODAY), java.sql.Timestamp.from(TODAY));
    }
}

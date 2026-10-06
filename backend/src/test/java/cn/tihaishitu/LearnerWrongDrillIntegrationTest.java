package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 快速练习错题（intent = wrong_drill）：
 * 随机连续刷 active 错题，Session 内不重复，全部做完后本轮结束；
 * 快速练习中答对不会自动移出错题本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-wrong-drill;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerWrongDrillIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired QuestionAttemptStore attempts;
    @Autowired cn.tihaishitu.learning.LearnerPracticeService practice;
    @Autowired cn.tihaishitu.learning.LearnerPracticeStore store;

    @Test
    void wrongDrillRunsEachActiveWrongQuestionOnceAndKeepsThemInTheBook() throws Exception {
        DrillFixture drill = fixture();
        Cookie cookie = register("wrong-drill-main");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='wrong-drill-main'", String.class);
        Set<String> wrong = new LinkedHashSet<>();
        for (int index = 0; index < 4; index++) {
            String question = question(drill.point(), "错题" + index, index + 1, drill.auxiliary());
            wrong.add(question);
            graded(learner, question, drill.point());
        }

        JsonNode session = start(cookie, "{\"intent\":\"wrong_drill\"}");
        String sessionId = session.path("id").asText();
        Set<String> served = new LinkedHashSet<>();
        for (int index = 0; index < 4; index++) {
            String questionId = session.path("currentAttempt").path("question").path("id").asText();
            assertThat(wrong).contains(questionId);
            assertThat(served.add(questionId)).as("Session 内不能重复出题").isTrue();
            // 正式做题页顶部信息：来源 + 真题标签 + 全部知识点标签（core / auxiliary 都返回）。
            JsonNode attempt = session.path("currentAttempt");
            assertThat(attempt.path("sourceName").asText()).startsWith("2022年全国硕士研究生招生考试数学一");
            assertThat(attempt.path("examYear").asInt()).isEqualTo(2022);
            assertThat(Integer.parseInt(attempt.path("questionNumber").asText())).isBetween(1, 4);
            assertThat(attempt.path("examLabel").asText()).isEqualTo("2022年考研数学一真题");
            assertThat(attempt.path("knowledgePoints")).hasSize(2);
            assertThat(java.util.List.of(attempt.path("knowledgePoints").get(0).path("role").asText(),
                            attempt.path("knowledgePoints").get(1).path("role").asText()))
                    .containsExactlyInAnyOrder("core", "auxiliary");
            session = answer(cookie, sessionId, session.path("currentAttempt").path("id").asText(),
                    questionId, session.path("currentAttempt").path("question").path("options"));
            if (index < 3) session = next(cookie, sessionId);
        }

        // 全部做完后本轮结束，不再发题，也不报 500。
        assertThat(session.path("canRepeat").asBoolean()).isFalse();
        String finalAttempt = session.path("currentAttempt").path("id").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM study_attempt WHERE practice_session_id=?",
                Integer.class, sessionId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_diagnosis_session WHERE practice_session_id=?",
                Integer.class, sessionId)).isZero();
        assertThat(finalAttempt).isNotBlank();
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", sessionId).with(csrf()).cookie(cookie))
                .andExpect(status().isConflict());

        // 答对不会自动移出错题本：4 道仍然全部 active。
        JsonNode queue = json(mvc.perform(get("/api/v1/learner/wrong-questions").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(queue).hasSize(4);
        assertThat(queue.get(0).path("knowledgePoints")).isNotEmpty();

        // 统计把 wrong_drill 计入错题练习作答。
        JsonNode statistics = json(mvc.perform(get("/api/v1/learner/statistics?days=7").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(statistics.path("summary").path("wrongReviewAttempts").asInt()).isEqualTo(4);
    }

    @Test
    void emptyWrongBookReturnsFriendlyErrorInsteadOfServerError() throws Exception {
        fixture();
        Cookie cookie = register("wrong-drill-empty");
        mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"wrong_drill\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recentChapterTracksActiveAndLastChapterPractice() throws Exception {
        Cookie cookie = register("wrong-drill-recent");
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String point = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'最近章节文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','最近章节','',0,1)", chapter, book);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,'最近知识点','测试','节','章','core','active','','',0,1)
                """, point, "RECENT-" + point);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, point, chapter);
        question(point, "最近章节题");

        // Chapter 可练范围来自 Selected Books，先显式选择这本文集。
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='wrong-drill-recent'", String.class);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);

        JsonNode none = json(mvc.perform(get("/api/v1/learner/practice-sessions/recent-chapter").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(none.path("status").asText()).isEqualTo("none");

        JsonNode session = start(cookie, "{\"intent\":\"chapter_drill\",\"targetBookId\":\"%s\",\"targetChapterId\":\"%s\"}"
                .formatted(book, chapter));
        String sessionId = session.path("id").asText();
        JsonNode active = json(mvc.perform(get("/api/v1/learner/practice-sessions/recent-chapter").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(active.path("status").asText()).isEqualTo("active");
        assertThat(active.path("activeSessionId").asText()).isEqualTo(sessionId);
        assertThat(active.path("bookName").asText()).isEqualTo("最近章节文集");
        assertThat(active.path("chapterName").asText()).isEqualTo("最近章节");
        assertThat(active.path("currentKnowledgePointIndex").asInt()).isEqualTo(1);
        assertThat(active.path("knowledgePointCount").asInt()).isEqualTo(1);

        // 结束本轮后仍然可以一键再次练习：status=last，前端用同一 Book + Chapter 新建 Session。
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/end", sessionId).with(csrf()).cookie(cookie))
                .andExpect(status().isOk());
        JsonNode last = json(mvc.perform(get("/api/v1/learner/practice-sessions/recent-chapter").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(last.path("status").asText()).isEqualTo("last");
        assertThat(last.path("activeSessionId").isMissingNode() || last.path("activeSessionId").isNull()).isTrue();
        assertThat(last.path("bookId").asText()).isEqualTo(book);
        assertThat(last.path("chapterId").asText()).isEqualTo(chapter);
        assertThat(last.path("updatedAt").asText()).isNotBlank();
    }

    private JsonNode start(Cookie cookie, String body) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** 提交冻结后的正确选项：选项在发卷时已打乱，因此按 attempt 的标准答案映射成显示键。 */
    private JsonNode answer(Cookie cookie, String session, String attempt, String question,
                            JsonNode options) throws Exception {
        String standard = mapper.readTree(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, attempt)).asText();
        String value = options.path(standard).asText();
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers", session)
                        .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":\"%s\"}"
                                .formatted(attempt, question, value)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode next(Cookie cookie, String session) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", session)
                        .with(csrf()).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"错题快练\",\"password\":\"password-123\"}"
                                .formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    /**
     * 建立一本 selected Book，返回知识点 ID 与一个 auxiliary 知识点 ID。
     * core + auxiliary 关系到同一道题时，做题页必须把两个标签都返回。
     */
    private DrillFixture fixture() {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String point = UUID.randomUUID().toString(), auxiliary = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'错题快练文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, book);
        insertPoint(point, "DRILL", "错题快练知识", "core");
        insertPoint(auxiliary, "DRILL-AUX", "辅助知识点", "auxiliary");
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, point, chapter);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,1)", book, auxiliary, chapter);
        return new DrillFixture(point, auxiliary);
    }

    private void insertPoint(String id, String prefix, String name, String role) {
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章',?,'active','','',0,1)
                """, id, prefix + "-" + id, name, role);
    }

    private String question(String point, String text) {
        return question(point, text, 1, null);
    }

    /**
     * 建立一道真题资源：带 exam_year / question_number / source_name，
     * 并可额外挂一个 auxiliary 知识点，用于验证做题页返回全部 KP 标签。
     */
    private String question(String point, String text, int questionNumber, String auxiliaryPoint) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,source_name,exam_year,question_number,
                    question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,
                    analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','real_exam','2022年全国硕士研究生招生考试数学一',2022,?,
                    'single_choice','single_choice','auto',?,'"B"','解析',2,'published',1)
                """, id, String.valueOf(questionNumber), text);
        LinkedHashMap<String, Boolean> options = new LinkedHashMap<>();
        options.put("A", false); options.put("B", true); options.put("C", false); options.put("D", false);
        int order = 0;
        for (var entry : options.entrySet()) {
            jdbc.update("""
                    INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order)
                    VALUES (?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), id, entry.getKey(), entry.getKey().toLowerCase(),
                    entry.getValue(), order++);
        }
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, point);
        if (auxiliaryPoint != null && !auxiliaryPoint.equals(point)) {
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'auxiliary',1)", id, auxiliaryPoint);
        }
        return id;
    }

    private void graded(String learner, String question, String point) throws Exception {
        String attemptId = UUID.randomUUID().toString();
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        options.put("A", "a"); options.put("B", "b"); options.put("C", "c"); options.put("D", "d");
        String snapshot = mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("id", question), java.util.Map.entry("subject", "测试"),
                java.util.Map.entry("chapter", "章"), java.util.Map.entry("presentationType", "single_choice"),
                java.util.Map.entry("gradingMode", "auto"), java.util.Map.entry("question", "选择"),
                java.util.Map.entry("options", options),
                java.util.Map.entry("answer", "B"), java.util.Map.entry("explanation", "解析"),
                java.util.Map.entry("difficulty", 2),
                java.util.Map.entry("knowledgePointIds", java.util.List.of(point))));
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,world_id,practice_session_id,question_id,
                    question_snapshot_json,standard_answer_json,status,grading_mode,grading_source,assessment,
                    target_knowledge_point_id,evidence_mode,question_difficulty,answered_at)
                VALUES (?,NULL,?,NULL,NULL,?,?,'"B"','active','auto',NULL,NULL,?,?,2,NULL)
                """, attemptId, learner, question, snapshot, point, "normal");
        var attempt = new QuestionAttemptStore.Snapshot(attemptId, null, learner, null, null, question,
                mapper.readTree(snapshot), mapper.readTree("\"B\""), "active", "auto", null, null,
                point, "normal", 2, null, null, null);
        attempts.recordAnswer(attempt, mapper.getNodeFactory().textNode("A"), false, Instant.now());
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    private record DrillFixture(String point, String auxiliary) {}
}

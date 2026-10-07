package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR3 普通正式训练单层化（Hub 侧）。
 *
 * <p>长期规则：KNOWLEDGE / CHAPTER / WRONG 做完一题就直接 grading、记 Mastery / Evidence /
 * Wrong Book，然后进入下一道普通正式题或完成当前流程。</p>
 *
 * <p>禁止自动：错题 → Remedial child、错题 → Dependency probe、错题 → Diagnosis session、
 * 错题 → retry parent。旧 diagnosis / remedial 表与代码仍然保留（另行直接测试），
 * 本类专门断言普通训练**不再**调用它们。</p>
 */
@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:hub-single-layer;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class HubPracticeSingleLayerIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;

    /**
     * 知识点专项客观题答错：多 KP 综合题以前会触发诊断状态机，
     * 现在必须直接对 root target KP 记 Evidence，且不产生任何诊断 / 补救痕迹。
     */
    @Test void knowledgeObjectiveWrongDoesNotNestDiagnosisAndRecordsRootTargetEvidence() throws Exception {
        Fixture fixture = compositeFixture("true_false");
        Cookie cookie = register("single-layer-objective", fixture.book());
        JsonNode session = startKnowledge(cookie, fixture.target());
        assertThat(session.path("currentAttempt").path("question").path("id").asText()).isEqualTo(fixture.question());

        JsonNode attempt = session.path("currentAttempt");
        String attemptId = attempt.path("id").asText();
        session = answer(cookie, session.path("id").asText(), attemptId, fixture.question(), false);

        assertThat(session.path("currentAttempt").path("assessment").asText()).isEqualTo("wrong");
        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertThat(session.path("canRepeat").asBoolean()).isFalse();
        assertNoDiagnosisNesting(session.path("id").asText(), attemptId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, attemptId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class,
                fixture.dependency())).isZero();
        // 本 Session 随机池耗尽：next 必须是明确的 409，而不是发诊断 / 补救题。
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", session.path("id").asText())
                        .with(csrf()).cookie(cookie)).andExpect(status().isConflict());
    }

    /** 知识点专项综合题 partial：同样直接对 root target KP 记账，不进入补救流程。 */
    @Test void knowledgeSolutionPartialDoesNotNestRemedialChildren() throws Exception {
        Fixture fixture = compositeFixture("solution");
        Cookie cookie = register("single-layer-solution", fixture.book());
        JsonNode session = startKnowledge(cookie, fixture.target());
        JsonNode attempt = session.path("currentAttempt");
        assertThat(attempt.path("question").path("id").asText()).isEqualTo(fixture.question());

        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/reveal", session.path("id").asText())
                        .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\"}"
                                .formatted(attempt.path("id").asText(), fixture.question())))
                .andExpect(status().isOk());
        session = json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/self-assess", session.path("id").asText())
                        .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"assessment\":\"partial\"}"
                                .formatted(attempt.path("id").asText(), fixture.question())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(session.path("currentAttempt").path("assessment").asText()).isEqualTo("partial");
        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertNoDiagnosisNesting(session.path("id").asText(), attempt.path("id").asText());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class,
                attempt.path("id").asText())).isOne();
        // partial 仍然写永久错题本（错题是长期资产，与诊断无关）。
        assertThat(jdbc.queryForObject("""
                SELECT status FROM learner_wrong_question WHERE learner_id=(SELECT id FROM learner_account WHERE username=?)
                   AND question_id=?
                """, String.class, "single-layer-solution", fixture.question())).isEqualTo("active");
    }

    /** 章节练习答错：下一题必须是确定性题序里的正常后继，不插诊断 / 补救子题。 */
    @Test void chapterWrongAdvancesToTheDeterministicSuccessor() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'章节单层化','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, book);
        String first = chapterPoint(book, chapter, "CHAPTER-SINGLE-1", 0);
        String second = chapterPoint(book, chapter, "CHAPTER-SINGLE-2", 1);
        Cookie cookie = register("single-layer-chapter", book);

        JsonNode session = json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"chapter_drill\",\"targetBookId\":\"%s\",\"targetChapterId\":\"%s\"}"
                                .formatted(book, chapter)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String firstQuestion = session.path("currentAttempt").path("question").path("id").asText();
        assertThat(session.path("currentKnowledgePointId").asText()).isEqualTo(first);
        String firstAttempt = session.path("currentAttempt").path("id").asText();
        session = answer(cookie, session.path("id").asText(), firstAttempt, firstQuestion, false);

        // 章节是连续模式：答错也直接完成这一道正式题，且永远可以继续下一题。
        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertThat(session.path("canRepeat").asBoolean()).isTrue();
        assertNoDiagnosisNesting(session.path("id").asText(), firstAttempt);

        session = json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", session.path("id").asText())
                        .with(csrf()).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String secondAttempt = session.path("currentAttempt").path("id").asText();
        assertThat(session.path("currentKnowledgePointId").asText()).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT evidence_mode FROM study_attempt WHERE id=?", String.class, secondAttempt))
                .isEqualTo("normal");
        assertThat(jdbc.queryForObject("SELECT draw_mode FROM study_attempt WHERE id=?", String.class, secondAttempt))
                .isEqualTo("chapter");
    }

    /**
     * 单题错题重做：指定题 graded 后流程完成，返回错题列表即可，不插诊断或补救子题。
     */
    @Test void wrongReviewCompletesAfterTheSingleQuestionWithoutChildren() throws Exception {
        Fixture fixture = compositeFixture("true_false");
        Cookie cookie = register("single-layer-wrong-review", fixture.book());
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username=?", String.class, "single-layer-wrong-review");
        jdbc.update("""
                INSERT INTO learner_wrong_question(learner_id,question_id,target_knowledge_point_id,
                    first_wrong_at,last_wrong_at,status)
                VALUES (?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'active')
                """, learner, fixture.question(), fixture.target());

        JsonNode session = json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"wrong_review\",\"sourceQuestionId\":\"%s\"}".formatted(fixture.question())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String attemptId = session.path("currentAttempt").path("id").asText();
        session = answer(cookie, session.path("id").asText(), attemptId, fixture.question(), true);

        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertThat(session.path("canRepeat").asBoolean()).isFalse();
        assertNoDiagnosisNesting(session.path("id").asText(), attemptId);
        // 答对不会自动移出永久错题本。
        assertThat(jdbc.queryForObject("SELECT status FROM learner_wrong_question WHERE learner_id=? AND question_id=?",
                String.class, learner, fixture.question())).isEqualTo("active");
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", session.path("id").asText())
                        .with(csrf()).cookie(cookie)).andExpect(status().isConflict());
    }

    @Test void objectiveNoIdeaIsFormalWrongWithoutFakeAnswerOrDiagnosis() throws Exception {
        Fixture fixture = compositeFixture("true_false");
        Cookie cookie = register("no-idea-objective", fixture.book());
        JsonNode session = startKnowledge(cookie, fixture.target());
        String sessionId = session.path("id").asText();
        String attemptId = session.path("currentAttempt").path("id").asText();
        session = json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/no-idea", sessionId)
                        .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\"}"
                                .formatted(attemptId, fixture.question())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(session.path("currentAttempt").path("assessment").asText()).isEqualTo("wrong");
        assertThat(jdbc.queryForObject("SELECT submitted_answer_json FROM answer_record WHERE attempt_id=?",
                String.class, attemptId)).isEqualTo("null");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?",
                Integer.class, attemptId)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_wrong_question WHERE last_wrong_attempt_id=?",
                Integer.class, attemptId)).isOne();
        assertNoDiagnosisNesting(sessionId, attemptId);
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/no-idea", sessionId).with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"attemptId\":\"%s\",\"questionId\":\"%s\"}"
                        .formatted(attemptId, fixture.question()))).andExpect(status().isConflict());
    }

    @Test void solutionNoIdeaWorksBeforeAndAfterReveal() throws Exception {
        for (boolean revealFirst : new boolean[]{false, true}) {
            Fixture fixture = compositeFixture("solution");
            Cookie cookie = register("no-idea-solution-" + revealFirst, fixture.book());
            JsonNode session = startKnowledge(cookie, fixture.target());
            String sessionId = session.path("id").asText(); String attemptId = session.path("currentAttempt").path("id").asText();
            if (revealFirst) mvc.perform(post("/api/v1/learner/practice-sessions/{id}/reveal", sessionId)
                    .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\"}".formatted(attemptId, fixture.question())))
                    .andExpect(status().isOk());
            session = json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/no-idea", sessionId)
                    .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\"}".formatted(attemptId, fixture.question())))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(session.path("currentAttempt").path("assessment").asText()).isEqualTo("wrong");
            assertThat(session.path("currentAttempt").path("explanation").asText()).contains("解析");
            assertNoDiagnosisNesting(sessionId, attemptId);
        }
    }

    /** 普通正式训练里不允许出现任何诊断会话、诊断题、training / remedial 子题。 */
    private void assertNoDiagnosisNesting(String sessionId, String rootAttemptId) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_diagnosis_session WHERE root_attempt_id=?", Integer.class, rootAttemptId))
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM study_attempt
                 WHERE practice_session_id=? AND (diagnosis_session_id IS NOT NULL OR diagnosis_role IS NOT NULL)
                """, Integer.class, sessionId)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM study_attempt WHERE practice_session_id=? AND evidence_mode IN ('training','remedial')
                """, Integer.class, sessionId)).isZero();
    }

    /** 只含 target KP 一道正式父题的书；该题 core=target、auxiliary=dependency，正是旧诊断的触发条件。 */
    private Fixture compositeFixture(String type) {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'单层化文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, book);
        String target = knowledge("SINGLE-T"), dependency = knowledge("SINGLE-D");
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, target, chapter);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,1)", book, dependency, chapter);
        String question = question(type, target, dependency);
        // 依赖知识点本身有题，才能证明「本来可以进入诊断」的场景现在也不再进入。
        question("true_false", dependency, null);
        return new Fixture(book, target, dependency, question);
    }

    private String chapterPoint(String book, String chapter, String name, int order) {
        String point = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',?,1)
                """, point, "CHAPTER-" + point, name, order);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)",
                book, point, chapter, order);
        question("true_false", point, null);
        return point;
    }

    private String knowledge(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, id, code + "-" + id, code);
        return id;
    }

    private String question(String type, String core, String auxiliary) {
        String id = UUID.randomUUID().toString();
        boolean solution = "solution".equals(type);
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom',?,?,?,?,NULL,'解析',2,'published',1)
                """, id, type, solution ? "self_assessment" : type, solution ? "self_assessment" : "auto",
                "题干-" + id);
        // Question Contract V2：客观题唯一答案事实是 question_resource_option.correct_option。
        if (!solution) QuestionFixtures.trueFalseOptions(jdbc, id);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, core);
        if (auxiliary != null)
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'auxiliary',1)", id, auxiliary);
        return id;
    }

    private Cookie register(String username, String book) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"单层化\",\"password\":\"password-123\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        // 注册会默认选中全部 enabled 文集；这里把学习范围收紧到本测试自己的文集。
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class, username);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
        return cookie;
    }

    private JsonNode startKnowledge(Cookie cookie, String point) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"knowledge_drill\",\"targetKnowledgePointId\":\"%s\"}".formatted(point)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /**
     * 按「答对 / 答错」意图作答。判断题选项会在 attempt 级重排并同步 remap boolean standard，
     * 因此不能固定提交 true / false，必须对照本次 attempt 冻结的 standard。
     */
    private JsonNode answer(Cookie cookie, String session, String attempt, String question, boolean correct) throws Exception {
        boolean standard = Boolean.parseBoolean(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, attempt));
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers", session)
                        .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attempt, question, correct == standard)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private record Fixture(String book, String target, String dependency, String question) {}
}

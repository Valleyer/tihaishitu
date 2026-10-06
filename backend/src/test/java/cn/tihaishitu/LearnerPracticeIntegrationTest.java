package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.game.KnowledgeQuestionPoolService;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-practice;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerPracticeIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
    @Autowired KnowledgeQuestionPoolService pool;

    @Test void explicitKnowledgeDrillUsesScopeWithoutRequiringDependencyMastery() throws Exception {
        DependencyFixture fixture = dependencyFixture("practice-scope-only");
        // 最新规则：不再存在“World 严格依赖 / Hub 只看范围”两套策略。
        // 前置知识点未掌握也不能阻止发题，hub 与 world 使用同一个候选池。
        var policy = new KnowledgeQuestionPoolService.AdaptiveQuestionPoolRequest(
                fixture.target(), Set.of(fixture.target(), fixture.dependency()), Set.of(), 2,
                KnowledgeQuestionPoolService.Mode.NORMAL);
        assertThat(pool.eligibleQuestionsForLearner(policy)).extracting(question -> question.id())
                .containsExactly(fixture.question());

        Cookie learner = register("practice-scope-only");
        String learnerId = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='practice-scope-only'", String.class);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learnerId);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learnerId, fixture.book());
        assertThat(pool.allowedKnowledgePointIds(Set.of(fixture.book())))
                .containsExactlyInAnyOrder(fixture.target(), fixture.dependency());
        JsonNode session = startKnowledge(learner, fixture.target());

        assertThat(session.path("currentAttempt").path("question").path("id").asText())
                .isEqualTo(fixture.question());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE knowledge_point_id=?",
                Integer.class, fixture.dependency())).isZero();
    }

    @Test void knowledgeDrillPersistsAttemptUpdatesMasteryAndDrawsAnotherSameTarget() throws Exception {
        Fixture fixture = fixture("practice-main");
        Cookie learner = register("practice-main");
        JsonNode session = startKnowledge(learner, fixture.point());
        String sessionId = session.path("id").asText();
        String attemptId = session.path("currentAttempt").path("id").asText();
        String questionId = session.path("currentAttempt").path("question").path("id").asText();

        session = answer(learner, sessionId, attemptId, questionId, true);
        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertThat(session.path("canRepeat").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?",
                Integer.class, attemptId)).isEqualTo(1);

        JsonNode restored = json(mvc.perform(get("/api/v1/learner/practice-sessions/{id}", sessionId)
                        .cookie(learner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode restoredAgain = json(mvc.perform(get("/api/v1/learner/practice-sessions/{id}", sessionId)
                        .cookie(learner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.path("currentAttempt").path("id").asText()).isEqualTo(attemptId);
        assertThat(restored.path("currentAttempt").path("status").asText()).isEqualTo("graded");
        assertThat(restored.path("currentAttempt").path("question"))
                .isEqualTo(restoredAgain.path("currentAttempt").path("question"));
        assertThat(restored.path("currentAttempt").path("standard"))
                .isEqualTo(restoredAgain.path("currentAttempt").path("standard"));

        JsonNode next = json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", sessionId)
                        .with(csrf()).cookie(learner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(next.path("targetKnowledgePointId").asText()).isEqualTo(fixture.point());
        assertThat(next.path("currentAttempt").path("question").path("id").asText()).isNotEqualTo(questionId);
        next = answer(learner, sessionId, next.path("currentAttempt").path("id").asText(),
                next.path("currentAttempt").path("question").path("id").asText(), true);
        assertThat(next.path("canRepeat").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT mastery_score FROM learner_knowledge_state WHERE learner_id=(SELECT id FROM learner_account WHERE username='practice-main') AND knowledge_point_id=?",
                Double.class, fixture.point())).isEqualTo(30d);
        assertThat(jdbc.queryForObject("SELECT evidence_count FROM learner_knowledge_state WHERE learner_id=(SELECT id FROM learner_account WHERE username='practice-main') AND knowledge_point_id=?",
                Integer.class, fixture.point())).isEqualTo(2);

        JsonNode listed = json(mvc.perform(get("/api/v1/learning/knowledge-points/{id}/questions", fixture.point())
                        .cookie(learner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        listed.forEach(question ->
                assertThat(question.path("learnerQuestionStatus").asText()).isEqualTo("mastered"));
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/end", sessionId).with(csrf()).cookie(learner))
                .andExpect(status().isOk());
        // 最新规则：今天已经答对的题仍然可以再练（重新开 Session 进入随机池），不再返回 400。
        mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(learner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"knowledge_drill\",\"targetKnowledgePointId\":\"%s\"}".formatted(fixture.point())))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_world_state", Integer.class)).isZero();
    }

    @Test void dueOrdinaryPracticeReusesQuestionWithANewFrozenOptionVariant() throws Exception {
        Fixture fixture = choiceFixture("practice-variant");
        Cookie learner = register("practice-variant");
        String learnerId = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='practice-variant'", String.class);
        JsonNode first = startKnowledge(learner, fixture.point());
        String firstSession = first.path("id").asText();
        String firstAttempt = first.path("currentAttempt").path("id").asText();
        String questionId = first.path("currentAttempt").path("question").path("id").asText();
        JsonNode firstOptions = first.path("currentAttempt").path("question").path("options");
        String firstStandard = mapper.readTree(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, firstAttempt)).asText();
        answerJson(learner, firstSession, firstAttempt, questionId, mapper.writeValueAsString(firstStandard));
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/end", firstSession).with(csrf()).cookie(learner))
                .andExpect(status().isOk());

        jdbc.update("UPDATE learner_knowledge_state SET last_evidence_at=DATEADD('DAY',-30,CURRENT_TIMESTAMP),stability_days=.5 WHERE learner_id=? AND knowledge_point_id=?",
                learnerId, fixture.point());
        jdbc.update("UPDATE study_attempt SET answered_at=DATEADD('DAY',-1,CURRENT_TIMESTAMP) WHERE id=?", firstAttempt);
        JsonNode second = startKnowledge(learner, fixture.point());
        String secondAttempt = second.path("currentAttempt").path("id").asText();
        JsonNode secondOptions = second.path("currentAttempt").path("question").path("options");
        String secondStandard = mapper.readTree(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, secondAttempt)).asText();

        assertThat(second.path("currentAttempt").path("question").path("id").asText()).isEqualTo(questionId);
        assertThat(secondOptions).isNotEqualTo(firstOptions);
        assertThat(secondStandard).isNotEqualTo(firstStandard);
        assertThat(secondOptions.path(secondStandard).asText()).isEqualTo("正确项");
    }

    @Test void failedParentRunsRemedialStepsOnceThenRetriesParentWithoutChildEvidence() throws Exception {
        // 最新规则下正式题抽取是随机的，因此用只含一道正式题的 fixture 保证父题唯一。
        SingleQuestionFixture fixture = singleQuestionFixture("practice-remedial");
        String parent = fixture.question();
        for (int order = 1; order <= 3; order++) {
            String child = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                        grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,
                        parent_question_id,derivation_type,derivation_order,training_goal,revision)
                    VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','子题解析',1,'published',
                        ?,'remedial_step',?,'逐步训练',1)
                    """, child, "子题" + order, parent, order);
        }
        Cookie learner = register("practice-remedial");
        JsonNode session = startKnowledge(learner, fixture.point());
        assertThat(session.path("currentAttempt").path("question").path("id").asText()).isEqualTo(parent);

        session = answer(learner, session.path("id").asText(), session.path("currentAttempt").path("id").asText(),
                parent, false);
        assertThat(session.path("flowComplete").asBoolean()).isFalse();
        for (int order = 1; order <= 3; order++) {
            session = next(learner, session.path("id").asText());
            assertThat(session.path("currentAttempt").path("evidenceMode").asText()).isEqualTo("remedial");
            assertThat(jdbc.queryForObject("SELECT derivation_order FROM question_resource WHERE id=?", Integer.class,
                    session.path("currentAttempt").path("question").path("id").asText())).isEqualTo(order);
            session = answer(learner, session.path("id").asText(), session.path("currentAttempt").path("id").asText(),
                    session.path("currentAttempt").path("question").path("id").asText(), true);
        }
        session = next(learner, session.path("id").asText());
        assertThat(session.path("currentAttempt").path("question").path("id").asText()).isEqualTo(parent);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=(SELECT id FROM learner_account WHERE username='practice-remedial')",
                Integer.class)).isOne();
        session = answer(learner, session.path("id").asText(), session.path("currentAttempt").path("id").asText(),
                parent, true);
        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE learner_id=(SELECT id FROM learner_account WHERE username='practice-remedial')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_question_mastery m JOIN question_resource q ON q.id=m.question_id WHERE q.parent_question_id IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test void chapterPracticeAdvancesKnowledgePointsInChapterOrder() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'章节练习','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章节','',0,1)", chapter, book);
        String first = chapterPoint(book, chapter, "章节知识一", 0);
        String second = chapterPoint(book, chapter, "章节知识二", 1);
        Cookie learner = register("practice-chapter");
        JsonNode session = json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(learner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"chapter_drill\",\"targetBookId\":\"%s\",\"targetChapterId\":\"%s\"}"
                                .formatted(book, chapter)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(session.path("currentKnowledgePointId").asText()).isEqualTo(first);
        session = answer(learner, session.path("id").asText(), session.path("currentAttempt").path("id").asText(),
                session.path("currentAttempt").path("question").path("id").asText(), true);
        session = next(learner, session.path("id").asText());
        assertThat(session.path("currentKnowledgePointId").asText()).isEqualTo(second);
    }

    private JsonNode startKnowledge(Cookie learner, String point) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(learner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"knowledge_drill\",\"targetKnowledgePointId\":\"%s\"}".formatted(point)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode answer(Cookie learner, String session, String attempt, String question, boolean answer) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers", session)
                        .with(csrf()).cookie(learner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attempt, question, answer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode answerJson(Cookie learner, String session, String attempt, String question, String answer) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers", session)
                        .with(csrf()).cookie(learner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}"
                                .formatted(attempt, question, answer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode next(Cookie learner, String session) throws Exception {
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", session)
                        .with(csrf()).cookie(learner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"专项\",\"password\":\"password-123\"}".formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private Fixture fixture(String prefix) {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String point = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?, '',TRUE,1,1)",
                book, prefix);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, book);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, point, prefix, prefix);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, point, chapter);
        question(point, prefix + "-q1", 2); question(point, prefix + "-q2", 4);
        return new Fixture(book, point);
    }

    /**
     * 只挂一道正式题的知识点：用于依赖“第一次一定抽到该题”的补救流程测试。
     * 正式题抽取现在随机，多个候选会让断言不确定。
     */
    private SingleQuestionFixture singleQuestionFixture(String prefix) {
        Fixture fixture = fixture(prefix);
        String first = jdbc.queryForObject(
                "SELECT id FROM question_resource WHERE content_markdown=? AND parent_question_id IS NULL",
                String.class, prefix + "-q1");
        String second = jdbc.queryForObject(
                "SELECT id FROM question_resource WHERE content_markdown=? AND parent_question_id IS NULL",
                String.class, prefix + "-q2");
        jdbc.update("DELETE FROM question_resource_knowledge WHERE question_id=?", second);
        jdbc.update("DELETE FROM question_resource WHERE id=?", second);
        return new SingleQuestionFixture(fixture.book(), fixture.point(), first);
    }

    private DependencyFixture dependencyFixture(String prefix) {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString(), dependency = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?, '',TRUE,1,1)",
                book, prefix);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, book);
        for (String point : List.of(target, dependency)) {
            jdbc.update("""
                    INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                        default_role,status,description,explanation,sort_order,revision)
                    VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                    """, point, prefix + "-" + point, point.equals(target) ? "目标" : "依赖");
            jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",
                    book, point, chapter);
        }
        String question = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','含未掌握依赖的题','true','解析',2,'published',1)
                """, question);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                question, target);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'auxiliary',1)",
                question, dependency);
        question(dependency, prefix + "-dependency-q", 2);
        return new DependencyFixture(book, target, dependency, question);
    }

    private Fixture choiceFixture(String prefix) {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String point = UUID.randomUUID().toString(), question = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?, '',TRUE,1,1)", book, prefix);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, book);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
                """, point, prefix, prefix);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, point, chapter);
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','single_choice','single_choice','auto','选择题','\"D\"','解析',2,'published',1)
                """, question);
        for (int index = 0; index < 4; index++) {
            String key = String.valueOf((char) ('A' + index));
            jdbc.update("INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), question, key, index == 3 ? "正确项" : "干扰项" + key,
                    index == 3, index);
        }
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", question, point);
        return new Fixture(book, point);
    }

    private void question(String point, String content) {
        question(point, content, 2);
    }
    private void question(String point, String content, int difficulty) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',?,'published',1)
                """, id, content, difficulty);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, point);
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
        question(point, name + "题", 2);
        return point;
    }
    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    private record Fixture(String book, String point) {}
    private record SingleQuestionFixture(String book, String point, String question) {}
    private record DependencyFixture(String book, String target, String dependency, String question) {}
}

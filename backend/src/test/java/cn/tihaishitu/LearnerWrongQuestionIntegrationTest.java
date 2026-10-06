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

import java.util.UUID;
import java.util.LinkedHashMap;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-wrong;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerWrongQuestionIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
    @Autowired QuestionAttemptStore attempts;

    @Test void wrongBookPersistsUntilManualRemovalAndReopensAfterAnotherFailure() throws Exception {
        Fixture f = fixture();
        Cookie cookie = register();
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='wrong-user'", String.class);
        graded(learner, f.q1(), f.point(), "wrong", "normal");

        JsonNode queue = json(mvc.perform(get("/api/v1/learner/wrong-questions").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).path("contentMarkdown").asText()).isEqualTo("原错题");
        JsonNode session = json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intent\":\"wrong_review\",\"sourceQuestionId\":\"%s\"}".formatted(f.q1())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(session.path("currentAttempt").path("question").path("id").asText()).isEqualTo(f.q1());
        JsonNode options = session.path("currentAttempt").path("question").path("options");
        assertThat(optionTexts(options)).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(optionTexts(options)).isNotEqualTo(java.util.List.of("a", "b", "c", "d"));
        String attemptId = session.path("currentAttempt").path("id").asText();
        String remappedAnswer = mapper.readTree(jdbc.queryForObject(
                "SELECT standard_answer_json FROM study_attempt WHERE id=?", String.class, attemptId)).asText();
        assertThat(options.path(remappedAnswer).asText()).isEqualTo("b");
        String sessionId = session.path("id").asText(), attempt = attemptId;
        mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers", sessionId).with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":\"%s\"}".formatted(attempt, f.q1(), remappedAnswer)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/learner/wrong-questions").cookie(cookie))
                .andExpect(status().isOk()).andExpect(result ->
                        assertThat(mapper.readTree(result.getResponse().getContentAsString())).hasSize(1));

        mvc.perform(delete("/api/v1/learner/wrong-questions/{id}", f.q1()).with(csrf()).cookie(cookie))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/learner/wrong-questions").cookie(cookie))
                .andExpect(status().isOk()).andExpect(result ->
                        assertThat(mapper.readTree(result.getResponse().getContentAsString())).isEmpty());

        graded(learner, f.q1(), f.point(), "wrong", "normal");
        graded(learner, f.q2(), f.point(), "partial", "normal");
        queue = json(mvc.perform(get("/api/v1/learner/wrong-questions").cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(queue).hasSize(2);
        assertThat(java.util.List.of(queue.get(0).path("questionId").asText(), queue.get(1).path("questionId").asText()))
                .containsExactlyInAnyOrder(f.q1(), f.q2());
    }

    private void graded(String learner, String question, String point, String assessment, String mode) throws Exception {
        String attemptId = UUID.randomUUID().toString();
        LinkedHashMap<String,String> options = new LinkedHashMap<>();
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
                VALUES (?,NULL,?,NULL,NULL,?,?,'\"B\"',?,? ,NULL,NULL,?,?,2,NULL)
                """, attemptId, learner, question, snapshot, "partial".equals(assessment) ? "revealed" : "active",
                "partial".equals(assessment) ? "self_assessment" : "auto", point, mode);
        var attempt = new QuestionAttemptStore.Snapshot(attemptId, null, learner, null, null, question,
                mapper.readTree(snapshot), mapper.readTree("\"B\""),
                "partial".equals(assessment) ? "revealed" : "active",
                "partial".equals(assessment) ? "self_assessment" : "auto", null, null,
                point, mode, 2, null, null, null);
        if ("partial".equals(assessment)) attempts.recordSelfAssessment(attempt, "partial", Instant.now());
        else attempts.recordAnswer(attempt, mapper.getNodeFactory().textNode("correct".equals(assessment) ? "B" : "A"),
                "correct".equals(assessment), Instant.now());
    }

    private Fixture fixture() {
        String book=UUID.randomUUID().toString(), chapter=UUID.randomUUID().toString(), point=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'错题文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)",chapter,book);
        jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,'WRONG-K','错题知识','测试','节','章','core','active','','',0,1)",point);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",book,point,chapter);
        String q1=question(point,"原错题"), q2=question(point,"部分正确题");
        return new Fixture(point,q1,q2);
    }
    private String question(String point,String text) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试','custom','single_choice','single_choice','auto',?,'\"B\"','解析',2,'published',1)",id,text);
        jdbc.batchUpdate("""
                INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order)
                VALUES (?,?,?,?,?,?)
                """, java.util.List.of(
                new Object[]{UUID.randomUUID().toString(),id,"A","a",false,0},
                new Object[]{UUID.randomUUID().toString(),id,"B","b",true,1},
                new Object[]{UUID.randomUUID().toString(),id,"C","c",false,2},
                new Object[]{UUID.randomUUID().toString(),id,"D","d",false,3}));
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",id,point);
        return id;
    }
    private Cookie register() throws Exception { return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"wrong-user\",\"displayName\":\"错题\",\"password\":\"password-123\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE); }
    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    private java.util.List<String> optionTexts(JsonNode options) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        options.elements().forEachRemaining(value -> values.add(value.asText()));
        return values;
    }
    private record Fixture(String point,String q1,String q2) {}
}

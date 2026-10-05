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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-practice;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerPracticeIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;

    @Test void knowledgeDrillPersistsAttemptUpdatesMasteryAndDrawsAnotherSameTarget() throws Exception {
        Fixture fixture = fixture("practice-main");
        Cookie learner = register("practice-main");
        JsonNode session = startKnowledge(learner, fixture.point());
        String sessionId = session.path("id").asText();
        String attemptId = session.path("currentAttempt").path("id").asText();
        String questionId = session.path("currentAttempt").path("question").path("id").asText();

        session = answer(learner, sessionId, attemptId, questionId, true);
        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?",
                Integer.class, attemptId)).isEqualTo(1);

        JsonNode restored = json(mvc.perform(get("/api/v1/learner/practice-sessions/{id}", sessionId)
                        .cookie(learner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(restored.path("currentAttempt").path("id").asText()).isEqualTo(attemptId);
        assertThat(restored.path("currentAttempt").path("status").asText()).isEqualTo("graded");

        JsonNode next = json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/next", sessionId)
                        .with(csrf()).cookie(learner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(next.path("targetKnowledgePointId").asText()).isEqualTo(fixture.point());
        assertThat(next.path("currentAttempt").path("question").path("id").asText()).isNotEqualTo(questionId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_world_state", Integer.class)).isZero();
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
        question(point, prefix + "-q1"); question(point, prefix + "-q2");
        return new Fixture(book, point);
    }

    private void question(String point, String content) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',2,'published',1)
                """, id, content);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", id, point);
    }
    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    private record Fixture(String book, String point) {}
}

package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.learning.AdaptiveStudyPlanner;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:learner-knowledge-state;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class LearnerKnowledgeStateIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
    @MockitoSpyBean AdaptiveStudyPlanner planner;

    @Test
    void officialWorldAttributesOnlyTargetAndKeepsWrongThenTrainingAsTwoEvidenceEvents() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String k1 = UUID.randomUUID().toString(), k2 = UUID.randomUUID().toString();
        String q1 = UUID.randomUUID().toString(), q2 = UUID.randomUUID().toString();
        insertBook(book, chapter); insertKnowledge(k1, "STATE-K1"); insertKnowledge(k2, "STATE-K2");
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, k1, chapter);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,1)", book, k2, chapter);
        insertQuestion(q1, 3); insertQuestion(q2, 2);
        for (String q : new String[]{q1, q2}) {
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", q, k1);
        }
        List<String> fillers = new ArrayList<>();
        for (int index = 3; index <= 6; index++) {
            String point = UUID.randomUUID().toString(), question = UUID.randomUUID().toString();
            fillers.add(point);
            insertKnowledge(point, "STATE-K" + index);
            jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,?)", book, point, chapter, index);
            insertQuestion(question, 2);
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", question, point);
        }
        Cookie learner = register();
        String learnerId = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='state-user'", String.class);
        jdbc.update("""
                INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,
                    target_difficulty,evidence_count,correct_streak,wrong_streak,last_outcome,last_evidence_at,
                    last_correct_at,model_version,revision)
                VALUES (?,?,100,365,2,1,0,0,'correct',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v1',1)
                """, learnerId, k2);
        jdbc.update("UPDATE learner_study_profile SET focus_mode='manual' WHERE learner_id=?", learnerId);
        jdbc.update("INSERT INTO learner_focus_knowledge(learner_id,knowledge_point_id,sort_order) VALUES (?,?,0)", learnerId, k1);
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        allowed.add(k1); allowed.add(k2); allowed.addAll(fillers);
        doAnswer(invocation -> {
            int count = invocation.getArgument(2);
            List<String> targets = new ArrayList<>(); targets.add(k1); targets.addAll(fillers);
            return new AdaptiveStudyPlanner.AdaptiveStudyPlan(allowed, java.util.Set.of(k2),
                    List.copyOf(targets.subList(0, count)));
        }).when(planner).randomPlan(anyString(), anySet(), anyInt());
        mvc.perform(get("/api/v1/learner/knowledge-states/{id}", k1).cookie(learner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.band").value("unstarted"))
                .andExpect(jsonPath("$.evidenceCount").value(0));
        mvc.perform(get("/api/v1/learner/knowledge-states").param("bookId", book).cookie(learner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE knowledge_point_id=?", Integer.class, k1)).isZero();
        initialize(learner);
        JsonNode game = json(mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(learner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String firstAttempt = game.path("attempt").path("id").asText();
        String firstQuestion = game.path("attempt").path("question").path("id").asText();
        assertThat(jdbc.queryForObject("SELECT target_knowledge_point_id FROM study_attempt WHERE id=?", String.class, firstAttempt)).isEqualTo(k1);
        assertThat(jdbc.queryForObject("SELECT evidence_mode FROM study_attempt WHERE id=?", String.class, firstAttempt)).isEqualTo("normal");
        game = answer(learner, firstAttempt, firstQuestion, false);
        answer(learner, firstAttempt, firstQuestion, false); // idempotent duplicate
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE attempt_id=?", Integer.class, firstAttempt)).isEqualTo(1);

        game = next(learner, game);
        String trainingAttempt = game.path("attempt").path("id").asText();
        String trainingQuestion = game.path("attempt").path("question").path("id").asText();
        assertThat(jdbc.queryForObject("SELECT evidence_mode FROM study_attempt WHERE id=?", String.class, trainingAttempt)).isEqualTo("training");
        answer(learner, trainingAttempt, trainingQuestion, true);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, k1)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_state WHERE knowledge_point_id=?", Integer.class, k1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?", Integer.class, k2)).isZero();
        assertThat(jdbc.queryForObject("SELECT evidence_count FROM learner_knowledge_state WHERE knowledge_point_id=?", Integer.class, k2)).isZero();
        assertThat(jdbc.queryForObject("SELECT evidence_count FROM learner_knowledge_state WHERE knowledge_point_id=?", Integer.class, k1)).isEqualTo(2);
        mvc.perform(get("/api/v1/learner/knowledge-states/{id}", k1).cookie(learner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.evidenceCount").value(2))
                .andExpect(jsonPath("$.lastOutcome").value("correct"));
    }

    private JsonNode answer(Cookie cookie, String attempt, String question, boolean value) throws Exception {
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/answers").with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}".formatted(attempt, question, value)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode next(Cookie cookie, JsonNode game) throws Exception {
        String attempt = game.path("attempt").path("id").asText();
        return json(mvc.perform(post("/api/v1/worlds/ancient-official/next").with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"attemptId\":\"%s\"}".formatted(attempt)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private Cookie register() throws Exception { return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"state-user\",\"displayName\":\"状态\",\"password\":\"password-123\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE); }
    private void initialize(Cookie cookie) throws Exception { mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie)
            .contentType(MediaType.APPLICATION_JSON).content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
            .andExpect(status().isCreated()); }
    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    private void insertBook(String book, String chapter) {
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'状态文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C1','章','',0,1)", chapter, book);
    }
    private void insertKnowledge(String id, String code) { jdbc.update("""
            INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision)
            VALUES (?,?,?,'测试','节','章','core','active','','',0,1)
            """, id, code, code); }
    private void insertQuestion(String id, int difficulty) { jdbc.update("""
            INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
            VALUES (?,'测试','custom','true_false','true_false','auto','判断题','true','解析',?,'published',1)
            """, id, difficulty); }
}

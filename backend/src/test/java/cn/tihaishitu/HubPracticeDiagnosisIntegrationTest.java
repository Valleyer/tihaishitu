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

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:hub-diagnosis;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class HubPracticeDiagnosisIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;

    @Test void compositeWrongUsesExistingDiagnosisStateMachineInsidePracticeContext() throws Exception {
        String book=UUID.randomUUID().toString(),chapter=UUID.randomUUID().toString();
        String target=knowledge("DIAG-T"), dependency=knowledge("DIAG-D");
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'诊断文集','',TRUE,1,1)",book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)",chapter,book);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",book,target,chapter);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,1)",book,dependency,chapter);
        question(target, dependency, "综合根题");
        question(dependency, null, "依赖核验");
        Cookie cookie=register();
        String learner=jdbc.queryForObject("SELECT id FROM learner_account WHERE username='hub-diag'",String.class);
        jdbc.update("""
                INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,
                    target_difficulty,evidence_count,correct_streak,wrong_streak,last_outcome,last_evidence_at,
                    last_correct_at,model_version,revision)
                VALUES (?,?,100,365,2,1,1,0,'correct',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'v1',1)
                """,learner,dependency);
        JsonNode session=json(mvc.perform(post("/api/v1/learner/practice-sessions").with(csrf()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"intent\":\"knowledge_drill\",\"targetKnowledgePointId\":\"%s\"}".formatted(target)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        question(target, null, "目标复查");
        String sessionId=session.path("id").asText();
        session=answer(cookie,session,false);
        assertThat(session.path("flowComplete").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?",Integer.class,target)).isZero();
        assertThat(jdbc.queryForObject("SELECT practice_session_id FROM learner_diagnosis_session WHERE root_attempt_id=?",
                String.class,session.path("currentAttempt").path("id").asText())).isEqualTo(sessionId);
        assertThat(jdbc.queryForObject("SELECT world_id FROM learner_diagnosis_session WHERE practice_session_id=?",
                String.class,sessionId)).isNull();

        session=next(cookie,sessionId);
        assertThat(session.path("currentAttempt").path("diagnosisRole").asText()).isEqualTo("dependency_probe");
        session=answer(cookie,session,true);
        session=next(cookie,sessionId);
        assertThat(session.path("currentAttempt").path("diagnosisRole").asText()).isEqualTo("target_remediation");
        session=answer(cookie,session,true);
        assertThat(session.path("flowComplete").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence WHERE knowledge_point_id=?",Integer.class,target)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_world_state",Integer.class)).isZero();
    }

    private JsonNode answer(Cookie cookie,JsonNode session,boolean value)throws Exception{
        JsonNode a=session.path("currentAttempt");
        return json(mvc.perform(post("/api/v1/learner/practice-sessions/{id}/answers",session.path("id").asText())
                .with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attemptId\":\"%s\",\"questionId\":\"%s\",\"answer\":%s}".formatted(
                        a.path("id").asText(),a.path("question").path("id").asText(),value)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode next(Cookie cookie,String id)throws Exception{return json(mvc.perform(
            post("/api/v1/learner/practice-sessions/{id}/next",id).with(csrf()).cookie(cookie))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private Cookie register()throws Exception{return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"hub-diag\",\"displayName\":\"诊断\",\"password\":\"password-123\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);}
    private String knowledge(String code){String id=UUID.randomUUID().toString();jdbc.update(
            "INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,'测试','节','章','core','active','','',0,1)",id,code,code);return id;}
    private void question(String core,String auxiliary,String text){String id=UUID.randomUUID().toString();jdbc.update(
            "INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',2,'published',1)",id,text);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",id,core);
        if(auxiliary!=null)jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'auxiliary',1)",id,auxiliary);}
    private JsonNode json(String value)throws Exception{return mapper.readTree(value);}
}

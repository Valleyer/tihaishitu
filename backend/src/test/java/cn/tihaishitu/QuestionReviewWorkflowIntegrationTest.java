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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:review-workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class QuestionReviewWorkflowIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;

    @Test void adminCanSelfReviewWhileReviewerRulesRemain() throws Exception {
        String point = knowledge();
        Cookie creator = account("review-creator", "CONTRIBUTOR", "REVIEWER");
        Cookie reviewer = account("review-other", "REVIEWER");
        Cookie admin = account("review-admin", "ADMIN");
        String source = source();
        String body = """
                {"subject":"测试","sourceId":"%s","sourceType":"custom","sourceName":"审核题","questionType":"true_false",
                 "presentationType":"true_false","gradingMode":"auto","content":"待审核题",
                 "analysis":"解析","difficulty":2,"options":[
                   {"key":"true","text":"正确","correct":true,"sortOrder":0},
                   {"key":"false","text":"错误","correct":false,"sortOrder":1}],
                 "knowledgePoints":[{"knowledgePointId":"%s","role":"core","sortOrder":0}]}
                """.formatted(source, point);
        JsonNode created = json(mvc.perform(post("/api/v1/manage/questions").with(csrf()).cookie(creator)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String id = created.path("id").asText();
        JsonNode pending = json(mvc.perform(post("/api/v1/manage/questions/{id}/submit", id).with(csrf()).cookie(creator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/manage/questions/{id}/review", id).with(csrf()).cookie(creator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"approve\":true,\"comment\":\"自审\"}"))
                .andExpect(status().isForbidden());
        JsonNode published = json(mvc.perform(post("/api/v1/manage/questions/{id}/review", id).with(csrf()).cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"approve\":true,\"comment\":\"审核通过并发布\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("published"))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/manage/questions/{id}/archive", id).with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":%d}".formatted(published.path("revision").asLong())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("archived"));
        assertThat(pending.path("createdBy").asText()).isEqualTo(
                jdbc.queryForObject("SELECT id FROM learner_account WHERE username='review-creator'", String.class));

        JsonNode adminCreated = json(mvc.perform(post("/api/v1/manage/questions").with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("待审核题", "管理员自审题")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String adminQuestionId = adminCreated.path("id").asText();
        mvc.perform(post("/api/v1/manage/questions/{id}/submit", adminQuestionId).with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending_review"));
        mvc.perform(post("/api/v1/manage/questions/{id}/review", adminQuestionId).with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"approve\":true,\"comment\":\"管理员审核通过\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("published"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM content_audit_log
                WHERE actor_learner_id = (SELECT id FROM learner_account WHERE username='review-admin')
                  AND entity_id = ? AND action_name = 'QUESTION_REVIEW_APPROVED'
                """, Integer.class, adminQuestionId)).isEqualTo(1);
    }

    @Test void reviewerCanBulkDisablePublishedQuestionsAndRestoreThemAtomically() throws Exception {
        String point = knowledge();
        Cookie creator = account("bulk-disable-creator-" + UUID.randomUUID(), "CONTRIBUTOR");
        Cookie reviewer = account("bulk-disable-reviewer-" + UUID.randomUUID(), "REVIEWER");
        String source = source();
        String body = """
                {"subject":"测试","sourceId":"%s","sourceType":"custom","sourceName":"批量禁用题",
                 "questionType":"true_false","presentationType":"true_false","gradingMode":"auto",
                 "content":"%s","analysis":"解析","difficulty":2,"options":[
                   {"key":"true","text":"正确","correct":true,"sortOrder":0},
                   {"key":"false","text":"错误","correct":false,"sortOrder":1}],
                 "knowledgePoints":[{"knowledgePointId":"%s","role":"core","sortOrder":0}]}
                """;

        JsonNode first = createPublished(creator, reviewer, body.formatted(source, "第一题", point));
        JsonNode second = createPublished(creator, reviewer, body.formatted(source, "第二题", point));
        String disable = """
                {"items":[
                  {"id":"%s","expectedRevision":%d},
                  {"id":"%s","expectedRevision":%d}
                ]}
                """.formatted(first.path("id").asText(), first.path("revision").asLong(),
                second.path("id").asText(), second.path("revision").asLong());

        mvc.perform(post("/api/v1/manage/questions/bulk-disable").with(csrf()).cookie(creator)
                        .contentType(MediaType.APPLICATION_JSON).content(disable))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/manage/questions/bulk-disable").with(csrf()).cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON).content(disable))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2))
                .andExpect(jsonPath("$.status").value("archived"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_resource WHERE id IN (?,?) AND status='archived'",
                Integer.class, first.path("id").asText(), second.path("id").asText())).isEqualTo(2);

        String restore = """
                {"items":[
                  {"id":"%s","expectedRevision":%d},
                  {"id":"%s","expectedRevision":%d}
                ]}
                """.formatted(first.path("id").asText(), first.path("revision").asLong() + 1,
                second.path("id").asText(), second.path("revision").asLong() + 1);
        mvc.perform(post("/api/v1/manage/questions/bulk-restore").with(csrf()).cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON).content(restore))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2))
                .andExpect(jsonPath("$.status").value("published"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_resource WHERE id IN (?,?) AND status='published'",
                Integer.class, first.path("id").asText(), second.path("id").asText())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM content_audit_log
                 WHERE entity_id IN (?,?) AND action_name='QUESTION_RESTORED'
                """, Integer.class, first.path("id").asText(), second.path("id").asText())).isEqualTo(2);
    }

    private JsonNode createPublished(Cookie creator, Cookie reviewer, String body) throws Exception {
        JsonNode created = json(mvc.perform(post("/api/v1/manage/questions").with(csrf()).cookie(creator)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String id = created.path("id").asText();
        JsonNode pending = json(mvc.perform(post("/api/v1/manage/questions/{id}/submit", id).with(csrf()).cookie(creator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":%d}".formatted(created.path("revision").asLong())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return json(mvc.perform(post("/api/v1/manage/questions/{id}/review", id).with(csrf()).cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":%d,\"approve\":true,\"comment\":\"发布\"}"
                                .formatted(pending.path("revision").asLong())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Cookie account(String username,String...roles) throws Exception {
        Cookie cookie=mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"displayName\":\"%s\",\"password\":\"password-123\"}".formatted(username,username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        String id=jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?",String.class,username);
        for(String role:roles) jdbc.update("INSERT INTO learner_account_role(learner_id,role_name) VALUES (?,?)",id,role);
        return cookie;
    }
    private String knowledge() {
        String id=UUID.randomUUID().toString();
        String code="REVIEW-K-" + id.substring(0, 8);
        jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,'测试','节','章','core','active','','',0,1)",id,code,"审核知识-" + id.substring(0, 8));
        return id;
    }
    private String source() {
        String id=UUID.randomUUID().toString();
        String name="审核题-" + id.substring(0, 8);
        jdbc.update("INSERT INTO question_source(id,source_type,canonical_name,display_name,status,revision) VALUES (?,'custom',?,?,'active',1)",id,name,name);
        return id;
    }
    private JsonNode json(String value)throws Exception{return mapper.readTree(value);}
}

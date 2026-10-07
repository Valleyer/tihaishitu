package cn.tihaishitu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import cn.tihaishitu.learner.LearnerAuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:management-policy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=policy-admin",
        "app.initial-admin.password=policy-admin-test-password"
})
class ManagementPolicyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void resetUsers() {
        ensureUser("policy-contributor", "贡献者", Set.of("CONTRIBUTOR"));
        ensureUser("policy-reviewer", "审核者", Set.of("REVIEWER"));
        ensureUser("policy-reviewer-two", "第二审核者", Set.of("REVIEWER"));
    }

    @Test
    void writeEndpointsRequireAuthenticationAndContributorsCannotPublishOrReviewDirectly() throws Exception {
        String pointId = activeKnowledgeId();
        ObjectNode payload = choiceQuestion(pointId, "single_choice");
        payload.put("status", "published");

        mvc.perform(post("/api/v1/manage/questions").with(csrf())
                        .contentType("application/json").content(payload.toString()))
                .andExpect(status().isUnauthorized());

        Cookie contributor = login("policy-contributor");
        Cookie reviewer = login("policy-reviewer");
        String created = mvc.perform(post("/api/v1/manage/questions").cookie(contributor).with(csrf())
                        .contentType("application/json").content(payload.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andReturn().getResponse().getContentAsString();
        JsonNode draft = mapper.readTree(created);
        String questionId = draft.path("id").asText();

        String submitted = mvc.perform(post("/api/v1/manage/questions/{id}/submit", questionId)
                        .cookie(contributor).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + draft.path("revision").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andReturn().getResponse().getContentAsString();
        long revision = mapper.readTree(submitted).path("revision").asLong();

        mvc.perform(post("/api/v1/manage/questions/{id}/review", questionId)
                        .cookie(contributor).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + revision + ",\"approve\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/manage/questions/{id}/review", questionId)
                        .cookie(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + revision + ",\"approve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void reviewersCannotReviewTheirOwnQuestions() throws Exception {
        Cookie reviewer = login("policy-reviewer");
        String created = mvc.perform(post("/api/v1/manage/questions").cookie(reviewer).with(csrf())
                        .contentType("application/json").content(choiceQuestion(activeKnowledgeId(), "single_choice").toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode draft = mapper.readTree(created);
        String submitted = mvc.perform(post("/api/v1/manage/questions/{id}/submit", draft.path("id").asText())
                        .cookie(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + draft.path("revision").asLong() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode pending = mapper.readTree(submitted);

        mvc.perform(post("/api/v1/manage/questions/{id}/review", pending.path("id").asText())
                        .cookie(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + pending.path("revision").asLong() + ",\"approve\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void questionTypesUseFixedContractsAndBlankIsRejectedEverywhere() throws Exception {
        Cookie contributor = login("policy-contributor");
        Cookie reviewer = login("policy-reviewer");
        String pointId = activeKnowledgeId();
        ObjectNode duplicate = choiceQuestion(pointId, "single_choice");
        ArrayNode relations = duplicate.withArray("knowledgePoints");
        relations.add(relations.get(0).deepCopy());
        mvc.perform(post("/api/v1/manage/questions").cookie(contributor).with(csrf())
                        .contentType("application/json").content(duplicate.toString()))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/manage/questions").cookie(contributor).with(csrf())
                        .contentType("application/json").content(choiceQuestion(pointId, "blank").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "万境求知不支持填空题；原填空题必须在生成阶段转换为单选题或多选题。"));

        for (String type : java.util.List.of("single_choice", "multiple_choice", "true_false", "solution")) {
            mvc.perform(post("/api/v1/manage/questions").cookie(contributor).with(csrf())
                            .contentType("application/json").content(questionForType(pointId, type).toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.questionType").value(type));
        }

        String legacyId = UUID.randomUUID().toString();
        String contributorId = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='policy-contributor'", String.class);
        jdbc.update("""
                INSERT INTO question_resource(
                    id,subject_name,source_type,question_type,presentation_type,grading_mode,
                    content_markdown,standard_answer_json,analysis_markdown,difficulty,status,created_by,revision)
                VALUES (?,'数学一','custom','blank','self_assessment','self_assessment',
                    '历史填空题','\"答案\"','历史解析',2,'pending_review',?,1)
                """, legacyId, contributorId);
        mvc.perform(post("/api/v1/manage/questions/{id}/review", legacyId)
                        .cookie(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":1,\"approve\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "万境求知不支持填空题；原填空题必须在生成阶段转换为单选题或多选题。"));
    }

    @Test
    void onlyAdminsManageRolesAndDisablingAnAccountRevokesExistingSessions() throws Exception {
        Cookie contributor = login("policy-contributor");
        mvc.perform(get("/api/v1/manage/users").cookie(contributor))
                .andExpect(status().isForbidden());

        Cookie admin = login("policy-admin");
        String users = mvc.perform(get("/api/v1/manage/users").cookie(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode target = null;
        for (JsonNode user : mapper.readTree(users)) {
            if ("policy-contributor".equals(user.path("username").asText())) target = user;
        }
        assertThat(target).isNotNull();
        String body = mapper.writeValueAsString(java.util.Map.of(
                "displayName", "贡献者兼审核者", "status", "active",
                "roles", Set.of("CONTRIBUTOR", "REVIEWER"),
                "expectedRevision", target.path("revision").asLong()));
        mvc.perform(put("/api/v1/manage/users/{id}", target.path("id").asText())
                        .cookie(admin).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@ == 'REVIEWER')]").exists());

        jdbc.update("UPDATE learner_account SET status = 'disabled' WHERE username = 'policy-contributor'");
        mvc.perform(get("/api/v1/manage/questions").cookie(contributor))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("请先登录管理后台。"));
        mvc.perform(post("/api/v1/manage/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"policy-contributor\",\"password\":\"policy-contributor-test-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ObjectNode choiceQuestion(String pointId, String questionType) {
        ObjectNode question = mapper.createObjectNode();
        question.put("subject", "数学一");
        question.put("sourceId", sourceId());
        question.put("sourceType", "custom");
        question.put("sourceName", "权限策略回归测试");
        question.put("questionType", questionType);
        question.put("presentationType", "single_choice");
        question.put("gradingMode", "auto");
        question.put("content", "若 $f(x)=x^2$，则 $f'(1)$ 等于多少？ " + UUID.randomUUID());
        question.put("standardAnswer", "A");
        question.put("analysis", "由幂函数求导公式可得 $f'(1)=2$。");
        question.put("difficulty", 1);
        ArrayNode options = question.putArray("options");
        options.add(option("A", "2", true, 0));
        options.add(option("B", "1", false, 1));
        question.putArray("knowledgePoints").addObject()
                .put("knowledgePointId", pointId).put("role", "core").put("sortOrder", 0);
        return question;
    }

    private ObjectNode questionForType(String pointId, String questionType) {
        ObjectNode question = choiceQuestion(pointId, questionType);
        if ("multiple_choice".equals(questionType)) {
            question.put("presentationType", "multiple_choice");
            ((ObjectNode) question.withArray("options").get(1)).put("correct", true);
            question.putArray("standardAnswer").add("A").add("B");
        } else if ("true_false".equals(questionType)) {
            question.put("presentationType", "true_false");
            question.put("standardAnswer", true);
            ArrayNode options = question.putArray("options");
            options.add(option("true", "正确", true, 0));
            options.add(option("false", "错误", false, 1));
        } else if ("solution".equals(questionType)) {
            question.put("presentationType", "self_assessment");
            question.put("gradingMode", "self_assessment");
            question.put("standardAnswer", "完整参考步骤");
            question.putArray("options");
        }
        return question;
    }

    private ObjectNode option(String key, String text, boolean correct, int sortOrder) {
        return mapper.createObjectNode().put("key", key).put("text", text)
                .put("correct", correct).put("sortOrder", sortOrder);
    }

    private String activeKnowledgeId() {
        return jdbc.queryForObject("SELECT id FROM global_knowledge_point WHERE status = 'active' ORDER BY sort_order LIMIT 1",
                String.class);
    }

    private String sourceId() {
        String id = jdbc.query("SELECT id FROM question_source WHERE source_type='custom' AND canonical_name='权限策略回归测试'",
                result -> result.next() ? result.getString(1) : null);
        if (id != null) return id;
        id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_source(id,source_type,canonical_name,display_name,status,revision) VALUES (?,'custom','权限策略回归测试','权限策略回归测试','active',1)", id);
        return id;
    }

    private Cookie login(String username) throws Exception {
        var result = mvc.perform(post("/api/v1/manage/auth/login").with(csrf())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "username", username, "password", username + "-test-password"))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private void ensureUser(String username, String displayName, Set<String> roles) {
        String id = jdbc.query("SELECT id FROM learner_account WHERE username = ?",
                result -> result.next() ? result.getString(1) : null, username);
        if (id == null) {
            id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO learner_account(id, username, display_name, password_hash, status) VALUES (?, ?, ?, ?, 'active')",
                    id, username, displayName, encoder.encode(username + "-test-password"));
        } else {
            jdbc.update("UPDATE learner_account SET display_name = ?, status = 'active' WHERE id = ?", displayName, id);
        }
        jdbc.update("DELETE FROM learner_account_role WHERE learner_id = ?", id);
        for (String role : roles) jdbc.update("INSERT INTO learner_account_role(learner_id, role_name) VALUES (?, ?)", id, role);
    }
}

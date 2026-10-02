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
import org.springframework.mock.web.MockHttpSession;
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

        MockHttpSession contributor = login("policy-contributor");
        MockHttpSession reviewer = login("policy-reviewer");
        String created = mvc.perform(post("/api/v1/manage/questions").session(contributor).with(csrf())
                        .contentType("application/json").content(payload.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andReturn().getResponse().getContentAsString();
        JsonNode draft = mapper.readTree(created);
        String questionId = draft.path("id").asText();

        String submitted = mvc.perform(post("/api/v1/manage/questions/{id}/submit", questionId)
                        .session(contributor).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + draft.path("revision").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andReturn().getResponse().getContentAsString();
        long revision = mapper.readTree(submitted).path("revision").asLong();

        mvc.perform(post("/api/v1/manage/questions/{id}/review", questionId)
                        .session(contributor).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + revision + ",\"approve\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/manage/questions/{id}/review", questionId)
                        .session(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + revision + ",\"approve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    @Test
    void reviewersCannotReviewTheirOwnQuestions() throws Exception {
        MockHttpSession reviewer = login("policy-reviewer");
        String created = mvc.perform(post("/api/v1/manage/questions").session(reviewer).with(csrf())
                        .contentType("application/json").content(choiceQuestion(activeKnowledgeId(), "single_choice").toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode draft = mapper.readTree(created);
        String submitted = mvc.perform(post("/api/v1/manage/questions/{id}/submit", draft.path("id").asText())
                        .session(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + draft.path("revision").asLong() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode pending = mapper.readTree(submitted);

        mvc.perform(post("/api/v1/manage/questions/{id}/review", pending.path("id").asText())
                        .session(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + pending.path("revision").asLong() + ",\"approve\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateKnowledgeIsRejectedAndBlankQuestionsCanUseChoicePresentation() throws Exception {
        MockHttpSession contributor = login("policy-contributor");
        String pointId = activeKnowledgeId();
        ObjectNode duplicate = choiceQuestion(pointId, "single_choice");
        ArrayNode relations = duplicate.withArray("knowledgePoints");
        relations.add(relations.get(0).deepCopy());
        mvc.perform(post("/api/v1/manage/questions").session(contributor).with(csrf())
                        .contentType("application/json").content(duplicate.toString()))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/manage/questions").session(contributor).with(csrf())
                        .contentType("application/json").content(choiceQuestion(pointId, "blank").toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionType").value("blank"))
                .andExpect(jsonPath("$.presentationType").value("single_choice"))
                .andExpect(jsonPath("$.gradingMode").value("auto"))
                .andExpect(jsonPath("$.options.length()").value(2));
    }

    @Test
    void onlyAdminsManageRolesAndDisablingAnAccountRevokesExistingSessions() throws Exception {
        MockHttpSession contributor = login("policy-contributor");
        mvc.perform(get("/api/v1/manage/users").session(contributor))
                .andExpect(status().isForbidden());

        MockHttpSession admin = login("policy-admin");
        String users = mvc.perform(get("/api/v1/manage/users").session(admin))
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
                        .session(admin).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@ == 'REVIEWER')]").exists());

        jdbc.update("UPDATE app_user SET status = 'disabled' WHERE username = 'policy-contributor'");
        mvc.perform(get("/api/v1/manage/questions").session(contributor))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("账号已停用，请联系管理员。"));
        mvc.perform(post("/api/v1/manage/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"policy-contributor\",\"password\":\"policy-contributor-test-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ObjectNode choiceQuestion(String pointId, String questionType) {
        ObjectNode question = mapper.createObjectNode();
        question.put("subject", "数学一");
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

    private ObjectNode option(String key, String text, boolean correct, int sortOrder) {
        return mapper.createObjectNode().put("key", key).put("text", text)
                .put("correct", correct).put("sortOrder", sortOrder);
    }

    private String activeKnowledgeId() {
        return jdbc.queryForObject("SELECT id FROM global_knowledge_point WHERE status = 'active' ORDER BY sort_order LIMIT 1",
                String.class);
    }

    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(post("/api/v1/manage/auth/login").with(csrf())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "username", username, "password", username + "-test-password"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void ensureUser(String username, String displayName, Set<String> roles) {
        String id = jdbc.query("SELECT id FROM app_user WHERE username = ?",
                result -> result.next() ? result.getString(1) : null, username);
        if (id == null) {
            id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO app_user(id, username, display_name, password_hash, status) VALUES (?, ?, ?, ?, 'active')",
                    id, username, displayName, encoder.encode(username + "-test-password"));
        } else {
            jdbc.update("UPDATE app_user SET display_name = ?, status = 'active' WHERE id = ?", displayName, id);
        }
        jdbc.update("DELETE FROM app_user_role WHERE user_id = ?", id);
        for (String role : roles) jdbc.update("INSERT INTO app_user_role(user_id, role_name) VALUES (?, ?)", id, role);
    }
}

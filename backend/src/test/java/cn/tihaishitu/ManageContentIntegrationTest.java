package cn.tihaishitu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:manage-content;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=platform-admin",
        "app.initial-admin.password=platform-admin-test-password"
})
class ManageContentIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    @BeforeEach
    void users() {
        ensureUser("contributor", "贡献者", "CONTRIBUTOR");
        ensureUser("reviewer", "审核者", "REVIEWER");
    }

    @Test
    void knowledgeSearchEditAndQuestionReviewWorkflowAreRoleProtected() throws Exception {
        MockHttpSession contributor = login("contributor");
        MockHttpSession reviewer = login("reviewer");

        String search = mvc.perform(get("/api/v1/manage/knowledge-points")
                        .session(contributor).param("query", "挖洞高斯"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("M1-H06-035"))
                .andReturn().getResponse().getContentAsString();
        JsonNode point = mapper.readTree(search).path("content").path(0);
        String pointId = point.path("id").asText();
        long pointRevision = point.path("revision").asLong();

        mvc.perform(put("/api/v1/manage/knowledge-points/{id}", pointId)
                        .session(contributor).with(csrf()).contentType("application/json")
                        .content(knowledgeUpdate(pointRevision)))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/manage/knowledge-points/{id}", pointId)
                        .session(reviewer).with(csrf()).contentType("application/json")
                        .content(knowledgeUpdate(pointRevision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(pointRevision + 1));
        mvc.perform(put("/api/v1/manage/knowledge-points/{id}", pointId)
                        .session(reviewer).with(csrf()).contentType("application/json")
                        .content(knowledgeUpdate(pointRevision)))
                .andExpect(status().isConflict());

        String created = mvc.perform(post("/api/v1/manage/questions")
                        .session(contributor).with(csrf()).contentType("application/json")
                        .content(solutionQuestion(pointId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.questionType").value("solution"))
                .andExpect(jsonPath("$.gradingMode").value("self_assessment"))
                .andExpect(jsonPath("$.knowledgePoints[0].code").value("M1-H06-035"))
                .andReturn().getResponse().getContentAsString();
        JsonNode question = mapper.readTree(created);
        String questionId = question.path("id").asText();
        long revision = question.path("revision").asLong();

        String submitted = mvc.perform(post("/api/v1/manage/questions/{id}/submit", questionId)
                        .session(contributor).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + revision + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending_review"))
                .andReturn().getResponse().getContentAsString();
        long reviewRevision = mapper.readTree(submitted).path("revision").asLong();

        mvc.perform(post("/api/v1/manage/questions/{id}/review", questionId)
                        .session(contributor).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + reviewRevision + ",\"approve\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/manage/questions/{id}/review", questionId)
                        .session(reviewer).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + reviewRevision
                                + ",\"approve\":true,\"comment\":\"内容与知识点标注通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
    }

    private String knowledgeUpdate(long revision) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of(
                "name", "有瑕点的高斯公式", "defaultRole", "core", "status", "active",
                "description", "处理积分区域含奇点时的高斯公式方法。", "explanation", "先去除奇点邻域再取极限。",
                "aliases", java.util.List.of("有奇点的高斯公式", "挖洞高斯", "挖球法", "去奇点高斯"),
                "expectedRevision", revision));
    }

    private String solutionQuestion(String pointId) throws Exception {
        return mapper.writeValueAsString(java.util.Map.ofEntries(
                java.util.Map.entry("subject", "数学一"), java.util.Map.entry("sourceType", "real_exam"),
                java.util.Map.entry("sourceName", "2024年全国硕士研究生招生考试"),
                java.util.Map.entry("examYear", 2024), java.util.Map.entry("questionNumber", "20"),
                java.util.Map.entry("questionType", "solution"),
                java.util.Map.entry("presentationType", "self_assessment"),
                java.util.Map.entry("gradingMode", "self_assessment"),
                java.util.Map.entry("content", "设 $\\Sigma$ 含一个奇点，计算曲面积分。"),
                java.util.Map.entry("standardAnswer", "参考答案"),
                java.util.Map.entry("analysis", "使用挖洞高斯法。"), java.util.Map.entry("difficulty", 4),
                java.util.Map.entry("options", java.util.List.of()),
                java.util.Map.entry("knowledgePoints", java.util.List.of(java.util.Map.of(
                        "knowledgePointId", pointId, "role", "core", "sortOrder", 0)))));
    }

    private MockHttpSession login(String username) throws Exception {
        var result = mvc.perform(post("/api/v1/manage/auth/login").with(csrf())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of(
                                "username", username, "password", username + "-test-password"))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void ensureUser(String username, String displayName, String role) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) return;
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO app_user(id, username, display_name, password_hash, status) VALUES (?, ?, ?, ?, 'active')",
                id, username, displayName, encoder.encode(username + "-test-password"));
        jdbc.update("INSERT INTO app_user_role(user_id, role_name) VALUES (?, ?)", id, role);
    }
}

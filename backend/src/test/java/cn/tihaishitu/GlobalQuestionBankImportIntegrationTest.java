package cn.tihaishitu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.tihaishitu.catalog.OfficialMath1BookBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import cn.tihaishitu.learner.LearnerAuthService;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:global-import;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.admin-key=machine-import-key",
        "app.initial-admin.username=import-admin",
        "app.initial-admin.password=import-admin-test-password"
})
class GlobalQuestionBankImportIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void machineImportIsAtomicIdempotentAndUsesGlobalKnowledgeCodes() throws Exception {
        String bankId = UUID.randomUUID().toString();
        String objectiveId = UUID.randomUUID().toString();
        String solutionId = UUID.randomUUID().toString();
        String payload = payload(bankId, objectiveId, solutionId, true, "M1-H06-035");

        mvc.perform(post("/api/v1/admin/global-question-banks/import")
                        .contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/admin/global-question-banks/import")
                        .header("X-Admin-Key", "machine-import-key")
                        .contentType("application/json").content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.questionCount").value(2))
                .andExpect(jsonPath("$.createdQuestions").value(2))
                .andExpect(jsonPath("$.updatedQuestions").value(0));

        mvc.perform(post("/api/v1/admin/global-question-banks/import")
                        .header("X-Admin-Key", "machine-import-key")
                        .contentType("application/json").content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdQuestions").value(0))
                .andExpect(jsonPath("$.updatedQuestions").value(2));

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id IN (?, ?)", objectiveId, solutionId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ?", bankId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM question_resource_knowledge qrk JOIN global_knowledge_point g ON g.id = qrk.knowledge_point_id WHERE qrk.question_id IN (?, ?) AND g.code = 'M1-H06-035'", objectiveId, solutionId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM content_audit_log WHERE entity_id = ? AND action_name = 'QUESTION_BANK_IMPORTED'", bankId)).isEqualTo(2);
        mvc.perform(get("/api/v1/question-banks/{id}", bankId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(2));
    }

    @Test
    void browserImportRequiresAdminAndUnknownKnowledgeRollsBackWholeBatch() throws Exception {
        Cookie admin = login();
        String bankId = UUID.randomUUID().toString();
        String objectiveId = UUID.randomUUID().toString();
        String solutionId = UUID.randomUUID().toString();

        mvc.perform(post("/api/v1/manage/imports/question-bank")
                        .cookie(admin).with(csrf()).contentType("application/json")
                        .content(payload(bankId, objectiveId, solutionId, false, "UNKNOWN-CODE")))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM question_bank WHERE id = ?", bankId)).isZero();

        mvc.perform(post("/api/v1/manage/imports/question-bank")
                        .cookie(admin).with(csrf()).contentType("application/json")
                        .content(payload(bankId, objectiveId, solutionId, false, "M1-H06-035")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.relationCount").value(2));
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id IN (?, ?) AND status = 'pending_review'", objectiveId, solutionId)).isEqualTo(2);
        mvc.perform(get("/api/v1/question-banks/{id}", bankId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(0))
                .andExpect(jsonPath("$.knowledgePoints.length()").value(0));

        long bankRevision = count("SELECT revision FROM question_bank WHERE id = ?", bankId);
        long questionRevision = count("SELECT revision FROM question_resource WHERE id = ?", objectiveId);
        mvc.perform(post("/api/v1/manage/questions/{id}/review", objectiveId)
                        .cookie(admin).with(csrf()).contentType("application/json")
                        .content("{\"expectedRevision\":" + questionRevision
                                + ",\"approve\":true,\"comment\":\"批量题目复核通过\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("published"));
        assertThat(count("SELECT revision FROM question_bank WHERE id = ?", bankId)).isEqualTo(bankRevision + 1);
        mvc.perform(get("/api/v1/question-banks/{id}", bankId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.knowledgePoints.length()").value(1));
    }

    @Test
    void v1RejectsDestructiveImportIntoKnowledgeDrivenBook() throws Exception {
        String questionId = UUID.randomUUID().toString();
        String solutionId = UUID.randomUUID().toString();
        long memberships = count("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID);
        long items = count("SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID);

        mvc.perform(post("/api/v1/admin/global-question-banks/import")
                        .header("X-Admin-Key", "machine-import-key")
                        .contentType("application/json")
                        .content(payload(OfficialMath1BookBootstrap.BOOK_ID, questionId, solutionId,
                                false, "M1-H06-035")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "global-question-batch/v2")));

        assertThat(count("SELECT COUNT(*) FROM question_bank_knowledge WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(memberships);
        assertThat(count("SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ?",
                OfficialMath1BookBootstrap.BOOK_ID)).isEqualTo(items);
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id IN (?, ?)",
                questionId, solutionId)).isZero();
    }

    private Cookie login() throws Exception {
        var response = mvc.perform(post("/api/v1/manage/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"import-admin\",\"password\":\"import-admin-test-password\"}"))
                .andExpect(status().isOk()).andReturn();
        return response.getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private String payload(String bankId, String objectiveId, String solutionId,
                           boolean publish, String knowledgeCode) throws Exception {
        Map<String, Object> bank = Map.of(
                "id", bankId, "name", "导入测试文集", "description", "全服题目导入测试",
                "enabled", true, "weight", 5);
        Map<String, Object> point = Map.of("code", knowledgeCode, "role", "core", "sortOrder", 0);
        Map<String, Object> objective = Map.ofEntries(
                Map.entry("id", objectiveId), Map.entry("subject", "数学一"),
                Map.entry("sourceType", "custom"), Map.entry("sourceName", "导入测试"),
                Map.entry("questionType", "true_false"), Map.entry("presentationType", "true_false"),
                Map.entry("gradingMode", "auto"), Map.entry("content", "高斯公式可直接跨越奇点使用。"),
                Map.entry("standardAnswer", false), Map.entry("analysis", "应先挖去奇点邻域。"),
                Map.entry("difficulty", 1),
                Map.entry("options", List.of(
                        Map.of("key", "true", "text", "正确", "correct", false, "sortOrder", 0),
                        Map.of("key", "false", "text", "错误", "correct", true, "sortOrder", 1))),
                Map.entry("knowledgePoints", List.of(point)));
        Map<String, Object> solution = Map.ofEntries(
                Map.entry("id", solutionId), Map.entry("subject", "数学一"),
                Map.entry("sourceType", "custom"), Map.entry("sourceName", "导入测试"),
                Map.entry("questionType", "solution"), Map.entry("presentationType", "self_assessment"),
                Map.entry("gradingMode", "self_assessment"), Map.entry("content", "计算含奇点的曲面积分。"),
                Map.entry("standardAnswer", "先挖去奇点邻域，再应用高斯公式并取极限。"),
                Map.entry("analysis", "完整参考步骤。"), Map.entry("difficulty", 4),
                Map.entry("options", List.of()), Map.entry("knowledgePoints", List.of(point)));
        return mapper.writeValueAsString(Map.of(
                "schemaVersion", "global-question-bank/v1", "publish", publish,
                "bank", bank, "questions", List.of(objective, solution)));
    }

    private long count(String sql, Object... params) {
        Long result = jdbc.queryForObject(sql, Long.class, params);
        return result == null ? 0 : result;
    }
}

package cn.tihaishitu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:question-batch-v2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.admin-key=question-batch-key",
        "app.initial-admin.username=batch-admin",
        "app.initial-admin.password=batch-admin-test-password"
})
class GlobalQuestionBatchImportIntegrationTest {
    private static final String KNOWLEDGE_CODE = "M1-H06-035";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void v2ImportsGlobalQuestionsWithoutChangingAnyBookTable() throws Exception {
        MockHttpSession admin = login();
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        BookSnapshot before = bookSnapshot();
        String payload = batch(2041, null, List.of(
                singleChoice(firstId, "1", "设函数在原点连续。", KNOWLEDGE_CODE),
                solution(secondId, "22", KNOWLEDGE_CODE)));

        mvc.perform(post("/api/v1/admin/questions/import")
                        .contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());

        String response = mvc.perform(post("/api/v1/manage/imports/questions")
                        .session(admin).with(csrf()).contentType("application/json").content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("global-question-batch/v2"))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.subject").value("数学一"))
                .andExpect(jsonPath("$.questionCount").value(2))
                .andExpect(jsonPath("$.optionCount").value(2))
                .andExpect(jsonPath("$.relationCount").value(2))
                .andExpect(jsonPath("$.createdQuestions").value(2))
                .andReturn().getResponse().getContentAsString();

        String importId = mapper.readTree(response).path("importId").asText();
        String actorId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = 'batch-admin'", String.class);
        assertThat(bookSnapshot()).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id IN (?, ?) AND status = 'pending_review'",
                firstId, secondId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM question_resource_option WHERE question_id = ?", firstId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM question_resource_knowledge WHERE question_id IN (?, ?)",
                firstId, secondId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT created_by FROM question_resource WHERE id = ?",
                String.class, firstId)).isEqualTo(actorId);
        assertThat(count("""
                SELECT COUNT(*) FROM content_audit_log
                 WHERE action_name = 'QUESTION_BATCH_IMPORTED' AND entity_type = 'question_batch'
                   AND entity_id = ?
                """, importId)).isEqualTo(1);
    }

    @Test
    void sameUuidUpdatesInPlaceAndInvalidatesPreviousReview() throws Exception {
        MockHttpSession admin = login();
        String id = UUID.randomUUID().toString();
        String first = batch(2042, true,
                List.of(singleChoice(id, "3", "旧题干", KNOWLEDGE_CODE)));
        mvc.perform(post("/api/v1/manage/imports/questions")
                        .session(admin).with(csrf()).contentType("application/json").content(first))
                .andExpect(status().isOk());
        String actorId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = 'batch-admin'", String.class);
        jdbc.update("""
                UPDATE question_resource
                   SET reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP, review_comment = '旧审核结论'
                 WHERE id = ?
                """, actorId, id);

        String second = batch(2042, false,
                List.of(singleChoice(id, "3", "修订后的题干", KNOWLEDGE_CODE)));
        mvc.perform(post("/api/v1/manage/imports/questions")
                        .session(admin).with(csrf()).contentType("application/json").content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdQuestions").value(0))
                .andExpect(jsonPath("$.updatedQuestions").value(1));

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM question_resource WHERE id = ?",
                Long.class, id)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT content_markdown FROM question_resource WHERE id = ?",
                String.class, id)).isEqualTo("修订后的题干");
        assertThat(jdbc.queryForObject("SELECT status FROM question_resource WHERE id = ?",
                String.class, id)).isEqualTo("pending_review");
        assertThat(count("""
                SELECT COUNT(*) FROM question_resource
                 WHERE id = ? AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_comment IS NULL
                """, id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM question_resource_option WHERE question_id = ?", id)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM question_resource_knowledge WHERE question_id = ?", id)).isEqualTo(1);
    }

    @Test
    void realExamNaturalIdentityRejectsDifferentUuid() throws Exception {
        String originalId = UUID.randomUUID().toString();
        String duplicateId = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json")
                        .content(batch(2043, false,
                                List.of(singleChoice(originalId, "5", "原题", KNOWLEDGE_CODE)))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json")
                        .content(batch(2043, false,
                                List.of(singleChoice(duplicateId, "5", "重复题", KNOWLEDGE_CODE)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UUID 不一致")));

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", originalId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", duplicateId)).isZero();
    }

    @Test
    void existingRealExamUuidCannotBeMovedToAnotherExamIdentity() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json")
                        .content(batch(2025, false,
                                List.of(singleChoice(id, "1", "2025 年原题", KNOWLEDGE_CODE)))))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json")
                        .content(batch(2026, false,
                                List.of(singleChoice(id, "1", "不应覆盖的新题", KNOWLEDGE_CODE)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Question UUID 已属于另一道题")));

        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT exam_year, question_number, content_markdown
                  FROM question_resource
                 WHERE id = ?
                """, id);
        assertThat(stored.get("exam_year")).isEqualTo(2025);
        assertThat(stored.get("question_number")).isEqualTo("1");
        assertThat(stored.get("content_markdown")).isEqualTo("2025 年原题");
    }

    @Test
    void unknownQuestionFieldRejectsWholeBatch() throws Exception {
        String id = UUID.randomUUID().toString();
        Map<String, Object> question = new LinkedHashMap<>(
                singleChoice(id, "8", "带有未知字段的题目", KNOWLEDGE_CODE));
        question.put("subject", "数学一");

        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json")
                        .content(batch(2046, false, List.of(question))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("第 1 道题包含未知字段：subject")));

        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", id)).isZero();
    }

    @Test
    void invalidLaterQuestionRollsBackWholeBatchAndBookFieldsAreRejected() throws Exception {
        String validId = UUID.randomUUID().toString();
        String invalidId = UUID.randomUUID().toString();
        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json")
                        .content(batch(2044, false, List.of(
                                singleChoice(validId, "1", "本应回滚", KNOWLEDGE_CODE),
                                singleChoice(invalidId, "2", "非法知识点", "UNKNOWN-KNOWLEDGE")))))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id IN (?, ?)",
                validId, invalidId)).isZero();

        String forbiddenId = UUID.randomUUID().toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> forbidden = mapper.readValue(
                batch(2045, false, List.of(singleChoice(forbiddenId, "1", "禁止 Book 字段", KNOWLEDGE_CODE))),
                LinkedHashMap.class);
        forbidden.put("bank", Map.of("id", UUID.randomUUID().toString()));
        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json").content(mapper.writeValueAsString(forbidden)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不得包含 Book 字段")));
        assertThat(count("SELECT COUNT(*) FROM question_resource WHERE id = ?", forbiddenId)).isZero();
    }

    @Test
    void repositoryExampleIsAcceptedByV2Importer() throws Exception {
        String example = Files.readString(Path.of("../frontend/public/examples/题库示例.json"));
        mvc.perform(post("/api/v1/admin/questions/import")
                        .header("X-Admin-Key", "question-batch-key")
                        .contentType("application/json").content(example))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaVersion").value("global-question-batch/v2"))
                .andExpect(jsonPath("$.questionCount").value(2))
                .andExpect(jsonPath("$.createdQuestions").value(2));
    }

    private MockHttpSession login() throws Exception {
        var response = mvc.perform(post("/api/v1/manage/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"batch-admin\",\"password\":\"batch-admin-test-password\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) response.getRequest().getSession(false);
    }

    private String batch(int year, Boolean publish, List<Map<String, Object>> questions) throws Exception {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", "global-question-batch/v2");
        if (publish != null) document.put("publish", publish);
        document.put("batch", Map.of(
                "subject", "数学一",
                "sourceType", "real_exam",
                "sourceName", "全国硕士研究生招生考试数学一",
                "examYear", year));
        document.put("questions", questions);
        return mapper.writeValueAsString(document);
    }

    private Map<String, Object> singleChoice(
            String id, String number, String content, String knowledgeCode) {
        return Map.ofEntries(
                Map.entry("id", id), Map.entry("questionNumber", number),
                Map.entry("questionType", "single_choice"),
                Map.entry("presentationType", "single_choice"), Map.entry("gradingMode", "auto"),
                Map.entry("content", content), Map.entry("standardAnswer", "A"),
                Map.entry("analysis", "本题解析。"), Map.entry("difficulty", 2),
                Map.entry("options", List.of(
                        Map.of("key", "A", "text", "正确项", "correct", true, "sortOrder", 0),
                        Map.of("key", "B", "text", "错误项", "correct", false, "sortOrder", 1))),
                Map.entry("knowledgePoints", List.of(
                        Map.of("code", knowledgeCode, "role", "core", "sortOrder", 0))));
    }

    private Map<String, Object> solution(String id, String number, String knowledgeCode) {
        return Map.ofEntries(
                Map.entry("id", id), Map.entry("questionNumber", number),
                Map.entry("questionType", "solution"),
                Map.entry("presentationType", "self_assessment"),
                Map.entry("gradingMode", "self_assessment"),
                Map.entry("content", "计算曲面积分。"),
                Map.entry("standardAnswer", "先挖去奇点邻域，再应用高斯公式。"),
                Map.entry("analysis", "完整参考步骤。"), Map.entry("difficulty", 4),
                Map.entry("options", List.of()),
                Map.entry("knowledgePoints", List.of(
                        Map.of("code", knowledgeCode, "role", "core", "sortOrder", 0))));
    }

    private BookSnapshot bookSnapshot() {
        return new BookSnapshot(
                count("SELECT COUNT(*) FROM question_bank"),
                count("SELECT COUNT(*) FROM question_bank_chapter"),
                count("SELECT COUNT(*) FROM question_bank_knowledge"),
                count("SELECT COUNT(*) FROM question_bank_item"));
    }

    private long count(String sql, Object... params) {
        Long result = jdbc.queryForObject(sql, Long.class, params);
        return result == null ? 0 : result;
    }

    private record BookSnapshot(long books, long chapters, long memberships, long legacyItems) {}
}

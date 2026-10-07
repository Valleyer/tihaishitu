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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:bulk-review;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class BulkReviewIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test void batchIsAtomicForAdminRevisionConflictsAndReviewerSelfReview() throws Exception {
        String point = knowledge();
        Cookie admin = account("bulk-admin", "ADMIN");
        Cookie reviewer = account("bulk-reviewer", "REVIEWER");
        Cookie contributor = account("bulk-contributor", "CONTRIBUTOR");

        JsonNode first = pending(admin, point, "A1");
        JsonNode second = pending(admin, point, "A2");
        mvc.perform(post("/api/v1/manage/questions/bulk-review").with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(batch(true, "批量通过", first, second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewed").value(2))
                .andExpect(jsonPath("$.approved").value(2)).andExpect(jsonPath("$.rejected").value(0));
        assertThat(statusOf(first)).isEqualTo("published");
        assertThat(statusOf(second)).isEqualTo("published");
        assertThat(auditCount(first, "QUESTION_REVIEW_APPROVED")).isOne();
        assertThat(auditCount(second, "QUESTION_REVIEW_APPROVED")).isOne();

        JsonNode conflictFirst = pending(admin, point, "C1");
        JsonNode conflictSecond = pending(admin, point, "C2");
        String conflictBody = """
                {"items":[{"id":"%s","expectedRevision":2},{"id":"%s","expectedRevision":99}],
                 "approve":true,"comment":"不应部分成功"}
                """.formatted(conflictFirst.path("id").asText(), conflictSecond.path("id").asText());
        mvc.perform(post("/api/v1/manage/questions/bulk-review").with(csrf()).cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(status().isConflict());
        assertThat(statusOf(conflictFirst)).isEqualTo("pending_review");
        assertThat(statusOf(conflictSecond)).isEqualTo("pending_review");

        JsonNode reviewerOwned = pending(reviewer, point, "R1");
        JsonNode otherOwned = pending(contributor, point, "R2");
        mvc.perform(post("/api/v1/manage/questions/bulk-review").with(csrf()).cookie(reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batch(false, "统一退回", otherOwned, reviewerOwned)))
                .andExpect(status().isForbidden());
        assertThat(statusOf(reviewerOwned)).isEqualTo("pending_review");
        assertThat(statusOf(otherOwned)).isEqualTo("pending_review");
    }

    private JsonNode pending(Cookie creator, String point, String number) throws Exception {
        String body = """
                {"subject":"测试","sourceId":"%s","sourceType":"custom","sourceName":"批量审核","questionNumber":"%s",
                 "questionType":"true_false","presentationType":"true_false","gradingMode":"auto",
                 "content":"题目 %s","standardAnswer":true,"analysis":"解析","difficulty":2,
                 "options":[{"key":"true","text":"正确","correct":true,"sortOrder":0},
                            {"key":"false","text":"错误","correct":false,"sortOrder":1}],
                 "knowledgePoints":[{"knowledgePointId":"%s","role":"core","sortOrder":0}]}
                """.formatted(sourceId(), number, number, point);
        JsonNode created = json(mvc.perform(post("/api/v1/manage/questions").with(csrf()).cookie(creator)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return json(mvc.perform(post("/api/v1/manage/questions/{id}/submit", created.path("id").asText())
                        .with(csrf()).cookie(creator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String batch(boolean approve, String comment, JsonNode... questions) {
        String items = java.util.Arrays.stream(questions)
                .map(question -> "{\"id\":\"%s\",\"expectedRevision\":%d}".formatted(
                        question.path("id").asText(), question.path("revision").asLong()))
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"items\":[%s],\"approve\":%s,\"comment\":\"%s\"}"
                .formatted(items, approve, comment);
    }

    private String statusOf(JsonNode question) {
        return jdbc.queryForObject("SELECT status FROM question_resource WHERE id=?", String.class,
                question.path("id").asText());
    }

    private int auditCount(JsonNode question, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE entity_id=? AND action_name=?",
                Integer.class, question.path("id").asText(), action);
    }

    private Cookie account(String username, String... roles) throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"%s\",\"password\":\"password-123\"}"
                                .formatted(username, username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
        String id = jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class, username);
        for (String role : roles) {
            jdbc.update("INSERT INTO learner_account_role(learner_id,role_name) VALUES (?,?)", id, role);
        }
        return cookie;
    }

    private String knowledge() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision) VALUES (?,?,?,?,?,?,?,?,?,?,0,1)",
                id, "BULK-" + id.substring(0, 8), "批量审核知识", "测试", "节", "章", "core", "active", "", "");
        return id;
    }

    private String sourceId() {
        String id = jdbc.query("SELECT id FROM question_source WHERE source_type='custom' AND canonical_name='批量审核'",
                result -> result.next() ? result.getString(1) : null);
        if (id != null) return id;
        id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_source(id,source_type,canonical_name,display_name,status,revision) VALUES (?,'custom','批量审核','批量审核','active',1)", id);
        return id;
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
}

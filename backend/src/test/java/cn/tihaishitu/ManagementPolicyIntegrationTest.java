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
                        "万境书院不支持填空题；原填空题必须在生成阶段转换为单选题或多选题。"));

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
                        "万境书院不支持填空题；原填空题必须在生成阶段转换为单选题或多选题。"));
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

    @Test
    void questionSearchSupportsStructuredYearNumberQuestionTypeAndNaturalDefaultOrder() throws Exception {
        // 全部使用独立来源，避免与种子题目或其他测试互相影响。
        String scope = UUID.randomUUID().toString().substring(0, 8);
        String sourceId = testSource("管理端检索来源-" + scope);
        String pointId = activeKnowledgeId();
        String standard = managedQuestion(sourceId, pointId, "single_choice", 2020, "7", "标准题号题");
        String legacy = managedQuestion(sourceId, pointId, "single_choice", 2020, "2020-7", "历史题号题");
        String otherYear = managedQuestion(sourceId, pointId, "single_choice", 2021, "7", "不同年份题");
        String first = managedQuestion(sourceId, pointId, "single_choice", 2020, "1", "第 1 题");
        String second = managedQuestion(sourceId, pointId, "single_choice", 2020, "2", "第 2 题");
        String tenth = managedQuestion(sourceId, pointId, "single_choice", 2020, "10", "第 10 题");
        String twentySecond = managedQuestion(sourceId, pointId, "single_choice", 2020, "22", "第 22 题");
        String solution = managedQuestion(sourceId, pointId, "solution", 2020, "3", "综合题");
        String draft = managedQuestion(sourceId, pointId, "multiple_choice", 2020, "4", "草稿多选题", "draft");

        Cookie contributor = login("policy-contributor");
        // “2020-7” 必须同时命中 question_number='7' 与历史写法 '2020-7'，且不能命中 2021 年。
        for (String query : java.util.List.of("2020-7", "2020 - 7", "2020—7")) {
            java.util.List<String> ids = searchIds(contributor, "query", query, "size", "50");
            assertThat(ids).as("query=%s", query).contains(standard, legacy);
            assertThat(ids).as("query=%s", query).doesNotContain(otherYear);
        }

        // 普通关键词搜索仍然有效，且状态筛选继续可按需使用（审核中心仍依赖它）。
        assertThat(searchIds(contributor, "query", "历史题号题", "size", "50")).contains(legacy);
        assertThat(searchIds(contributor, "status", "pending_review", "size", "50")).doesNotContain(draft);
        assertThat(searchIds(contributor, "status", "draft", "size", "50")).contains(draft);

        // 题型筛选：普通题目页只发送 questionType，不再发送 status。
        assertThat(searchIds(contributor, "questionType", "multiple_choice", "size", "100")).contains(draft);

        // 默认排序：来源 → 年份 → 题号自然排序 → question_id。
        // 本来源题号的 canonical 值是：first=1, second=2, solution=3, draft=4,
        // standard=7, legacy("2020-7")=7, tenth=10, twentySecond=22, otherYear=1（2021 年）。
        // standard 与 legacy 的 canonical 题号都是 7，二者之间没有约定顺序，
        // 由随机 question_id 稳定兜底——因此**不能**断言谁先谁后。
        // 同一个 JVM 共享内存库，因此只在“本来源的 9 道题”范围内断言，
        // 不用 containsExactly 绑定整个库的规模。
        java.util.List<String> ids = searchIds(contributor, "sourceType", "custom", "sourceId", sourceId, "size", "50");
        java.util.List<String> mine = java.util.List.of(first, second, solution, draft, standard, tenth,
                twentySecond, legacy, otherYear);
        java.util.List<String> ourOrder = ids.stream().filter(mine::contains).toList();
        assertThat(ourOrder).containsExactlyInAnyOrder(first, second, solution, draft, standard,
                legacy, tenth, twentySecond, otherYear);
        // 双保险：每个字段都必须真的出现在结果里，否则下面的 indexOf 会拿到 -1。
        assertThat(ids).containsAll(mine);
        // 纯数字题号按 1 < 2 < 3 < 4 的自然顺序。
        assertThat(ids.indexOf(first)).isLessThan(ids.indexOf(second));
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(solution));
        assertThat(ids.indexOf(solution)).isLessThan(ids.indexOf(draft));
        // "7" 与 "2020-7" canonical 相同，都排在 4 之后、10 之前，且二者相邻。
        // 谁在前由 question_id 决定，两种顺序都合法。
        assertThat(ids.indexOf(standard)).isBetween(ids.indexOf(draft) + 1, ids.indexOf(tenth) - 1);
        assertThat(ids.indexOf(legacy)).isBetween(ids.indexOf(draft) + 1, ids.indexOf(tenth) - 1);
        assertThat(Math.abs(ids.indexOf(standard) - ids.indexOf(legacy))).isEqualTo(1);
        // 字符串排序会得到 1, 10, 2, 20 这种顺序，这里必须证明不是字符串排序。
        assertThat(ids.indexOf(second)).isLessThan(ids.indexOf(tenth));
        assertThat(ids.indexOf(tenth)).isLessThan(ids.indexOf(twentySecond));
        // 2021 年的 otherYear 排在所有 2020 年题之后。
        assertThat(ids.indexOf(twentySecond)).isLessThan(ids.indexOf(otherYear));
    }

    /**
     * 非纯数字题号不能被猜成数字；列表展示也要用后端格式化的 displayQuestionNumber，
     * 不能把 examYear 与历史年份前缀题号拼成 "2020-2020-7"。
     */
    @Test
    void nonNumericQuestionNumbersKeepOrderAndListExposesDisplayNumber() throws Exception {
        String scope = UUID.randomUUID().toString().substring(0, 8);
        String sourceId = testSource("管理端题号展示来源-" + scope);
        String pointId = activeKnowledgeId();
        String one = managedQuestion(sourceId, pointId, "single_choice", 2020, "1", "第 1 题");
        String two = managedQuestion(sourceId, pointId, "single_choice", 2020, "2", "第 2 题");
        String legacy = managedQuestion(sourceId, pointId, "single_choice", 2020, "2020-7", "历史题号题");
        String ten = managedQuestion(sourceId, pointId, "single_choice", 2020, "10", "第 10 题");
        String trailingA = managedQuestion(sourceId, pointId, "single_choice", 2020, "21A", "21A 题");
        String dashA = managedQuestion(sourceId, pointId, "single_choice", 2020, "A-3", "A-3 题");
        String bracketed = managedQuestion(sourceId, pointId, "single_choice", 2020, "3(1)", "3(1) 题");

        Cookie contributor = login("policy-contributor");
        java.util.List<String> ids = searchIds(contributor, "sourceType", "custom", "sourceId", sourceId, "size", "50");
        java.util.List<String> mine = java.util.List.of(one, two, ten, legacy, trailingA, dashA, bracketed);
        assertThat(ids).containsAll(mine);
        // 纯数字题号按自然顺序；"2020-7" 落在 2 与 10 之间。
        assertThat(ids.indexOf(one)).isLessThan(ids.indexOf(two));
        assertThat(ids.indexOf(two)).isLessThan(ids.indexOf(legacy));
        assertThat(ids.indexOf(legacy)).isLessThan(ids.indexOf(ten));
        // 三个非纯数字题号都排在 10 之后，不会被错误插进 1–10 的自然序列。
        for (String nonNumeric : java.util.List.of(trailingA, dashA, bracketed)) {
            assertThat(ids.indexOf(nonNumeric)).as("题号分档").isGreaterThan(ids.indexOf(ten));
        }

        // displayQuestionNumber 由后端统一格式化：raw "2020-7" → "7"，raw "1" → "1"。
        String body = rawSearch(contributor, "sourceType", "custom", "sourceId", sourceId, "size", "50");
        JsonNode content = mapper.readTree(body).path("content");
        java.util.Map<String, JsonNode> byId = new java.util.LinkedHashMap<>();
        content.forEach(item -> byId.put(item.path("id").asText(), item));
        assertThat(byId.get(legacy).path("questionNumber").asText()).isEqualTo("2020-7");
        assertThat(byId.get(legacy).path("displayQuestionNumber").asText()).isEqualTo("7");
        assertThat(byId.get(one).path("questionNumber").asText()).isEqualTo("1");
        assertThat(byId.get(one).path("displayQuestionNumber").asText()).isEqualTo("1");
        assertThat(byId.get(trailingA).path("displayQuestionNumber").asText()).isEqualTo("21A");
        assertThat(byId.get(dashA).path("displayQuestionNumber").asText()).isEqualTo("A-3");
    }


    private java.util.List<String> searchIds(Cookie actor, String... params) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        String body = rawSearch(actor, params);
        mapper.readTree(body).path("content").forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    /** 原始响应体：用于断言 displayQuestionNumber 等字段。 */
    private String rawSearch(Cookie actor, String... params) throws Exception {
        var request = get("/api/v1/manage/questions").cookie(actor);
        for (int index = 0; index < params.length; index += 2) request = request.param(params[index], params[index + 1]);
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String testSource(String displayName) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_source(id,source_type,canonical_name,display_name,status,revision)
                VALUES (?,'custom',?,?,'active',1)
                """, id, "canonical-" + id, displayName);
        return id;
    }

    /** 直接写库构造管理端列表 fixture：列表查询只看 question_resource 与来源解析。 */
    private String managedQuestion(String sourceId, String pointId, String questionType,
                                   Integer examYear, String questionNumber, String content) {
        return managedQuestion(sourceId, pointId, questionType, examYear, questionNumber, content, "published");
    }

    private String managedQuestion(String sourceId, String pointId, String questionType,
                                   Integer examYear, String questionNumber, String content, String status) {
        String id = UUID.randomUUID().toString();
        String presentation = "solution".equals(questionType) ? "self_assessment" : questionType;
        String grading = "solution".equals(questionType) ? "self_assessment" : "auto";
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_id,source_type,source_name,exam_year,question_number,
                    question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,
                    difficulty,status,revision)
                VALUES (?,'数学一',?,'custom','管理端检索来源',?,?,?,?,?,?,NULL,'解析',1,?,1)
                """, id, sourceId, examYear, questionNumber, questionType, presentation, grading, content, status);
        jdbc.update("""
                INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order)
                VALUES (?,?,'core',0)
                """, id, pointId);
        return id;
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
        } else if ("true_false".equals(questionType)) {
            question.put("presentationType", "true_false");
            ArrayNode options = question.putArray("options");
            options.add(option("true", "正确", true, 0));
            options.add(option("false", "错误", false, 1));
        } else if ("solution".equals(questionType)) {
            question.put("presentationType", "self_assessment");
            question.put("gradingMode", "self_assessment");
            question.put("analysis", "## 参考答案\n\n完整参考步骤\n\n## 解析\n\n完整解析");
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

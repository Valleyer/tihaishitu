package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全平台题库（{@code GET /api/v1/learning/questions}）的只读浏览边界。
 *
 * <p>覆盖 PR4 的核心承诺：只返回 published Formal Parent Question、按 Question ID 去重、
 * 与 Learner 当前 selected Books 解耦、支持筛选与结构化题号搜索、默认按
 * 来源 → 年份 → 题号自然排序 → question_id 排序，并且浏览不创建 Attempt。</p>
 *
 * <p>每个测试自己写入 fixture（名称带 method 后缀），断言都通过测试自己的 sourceId
 * 限定范围，避免受种子数据规模影响，也不依赖测试执行顺序。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:question-directory;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=directory-admin",
        "app.initial-admin.password=directory-admin-test-password"
})
class QuestionDirectoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private String sourceId;
    private String otherSourceId;
    private String bookId;
    private String chapterId;
    private String otherBookId;
    private String otherChapterId;
    private String pointId;
    private String secondPointId;
    private String otherPointId;

    @BeforeEach
    void fixtures() {
        String scope = UUID.randomUUID().toString().substring(0, 8);
        sourceId = source("真题来源-" + scope, "real_exam");
        otherSourceId = source("自建来源-" + scope, "custom");
        bookId = book("题库测试文集甲-" + scope);
        chapterId = chapter(bookId, "C1", "第一章", 0);
        otherBookId = book("题库测试文集乙-" + scope);
        otherChapterId = chapter(otherBookId, "C1", "第一章", 0);
        pointId = knowledgePoint("DIR-K1-" + scope, "极限计算");
        secondPointId = knowledgePoint("DIR-K2-" + scope, "连续与间断");
        otherPointId = knowledgePoint("DIR-K3-" + scope, "未选文集知识点");
    }

    @Test
    void listsOnlyPublishedFormalParentsDeduplicatedAndIndependentOfSelectedBooks() throws Exception {
        // 同一道题关联两个知识点、属于两本文集：卡片与 total 都必须只算一次。
        String shared = published(sourceId, "single_choice", 2020, "7", 1, "共享题",
                List.of(new Relation(pointId, 0), new Relation(secondPointId, 1)),
                List.of(new Membership(bookId, chapterId), new Membership(otherBookId, otherChapterId)));
        String solution = published(sourceId, "solution", 2020, "8", 3, "综合题",
                List.of(new Relation(pointId, 0)), List.of());
        String draft = question(sourceId, "single_choice", 2020, "9", 1, "草稿题", "draft");
        relate(draft, pointId, "core", 0);
        String rejected = question(sourceId, "single_choice", 2020, "10", 1, "退回题", "rejected");
        relate(rejected, pointId, "core", 0);
        String archived = question(sourceId, "single_choice", 2020, "11", 1, "归档题", "archived");
        relate(archived, pointId, "core", 0);
        String child = question(sourceId, "single_choice", 2020, "12", 1, "补救子题", "published");
        jdbc.update("UPDATE question_resource SET parent_question_id=?, derivation_type='remedial_step' WHERE id=?",
                shared, child);
        relate(child, pointId, "core", 0);
        // 属于 Learner 尚未选择文集、且不属于任何文集关系的题也必须可见。
        String unselected = published(otherSourceId, "true_false", 2021, "3", 2, "未选文集题",
                List.of(new Relation(otherPointId, 0)), List.of());

        Cookie learner = register();
        // Learner 只选择文集甲。
        String learnerId = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='directory_user'", String.class);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learnerId);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learnerId, bookId);

        JsonNode page = getJson(learner, "/api/v1/learning/questions", "sourceId", sourceId);
        assertThat(page.path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(ids(page)).containsExactlyInAnyOrder(shared, solution);
        for (String hidden : List.of(draft, rejected, archived, child)) {
            assertThat(ids(page)).doesNotContain(hidden);
        }
        // 未 selected 文集的自建题仍然可以浏览。
        JsonNode other = getJson(learner, "/api/v1/learning/questions", "sourceId", otherSourceId);
        assertThat(other.path("totalElements").asInt()).isEqualTo(1);
        assertThat(other.path("content").get(0).path("id").asText()).isEqualTo(unselected);
    }

    @Test
    void filtersBySourceYearTypeDifficultyBookChapterAndKnowledge() throws Exception {
        String choiceA = published(sourceId, "single_choice", 2020, "1", 1, "极限题一",
                List.of(new Relation(pointId, 0)), List.of(new Membership(bookId, chapterId)));
        String choiceB = published(sourceId, "single_choice", 2021, "2", 4, "极限题二",
                List.of(new Relation(secondPointId, 0)), List.of(new Membership(otherBookId, otherChapterId)));
        String multi = published(sourceId, "multiple_choice", 2020, "3", 1, "多选题",
                List.of(new Relation(pointId, 0)), List.of());

        Cookie learner = register();
        // 每次断言都带上本题的 sourceId：同一个 JVM 共享内存库，
        // 只用本来源自己的题验证过滤语义，不绑定全库规模。
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "examYear", "2020"))).containsExactlyInAnyOrder(choiceA, multi);
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "questionType", "multiple_choice"))).containsExactly(multi);
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "difficulty", "4"))).containsExactly(choiceB);
        // bookId 是全局过滤，只能断言“包含本题、不包含别本文集的题”。
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "bookId", bookId))).contains(choiceA).doesNotContain(choiceB);
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "bookId", bookId, "chapterId", chapterId)))
                .containsExactlyInAnyOrder(choiceA, multi);
        // 另一本文集的章节不能命中本来源的题。
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "chapterId", otherChapterId))).containsExactly(choiceB);
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "knowledge", secondPointId))).containsExactly(choiceB);
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "knowledge", "连续与间断"))).containsExactly(choiceB);
        // 题干 / 来源 / 题号的普通关键词搜索仍然有效。
        assertThat(ids(getJson(learner, "/api/v1/learning/questions",
                "sourceId", sourceId, "query", "极限题二"))).containsExactly(choiceB);
    }

    @Test
    void structuredYearNumberQueryMatchesStandardAndLegacyQuestionNumbers() throws Exception {
        String standard = published(sourceId, "single_choice", 2020, "7", 1, "标准写法题",
                List.of(new Relation(pointId, 0)), List.of());
        String legacy = published(sourceId, "single_choice", 2020, "2020-7", 1, "历史写法题",
                List.of(new Relation(pointId, 0)), List.of());
        published(sourceId, "single_choice", 2021, "7", 1, "年份不同不该命中",
                List.of(new Relation(pointId, 0)), List.of());

        Cookie learner = register();
        for (String query : List.of("2020-7", "2020 - 7", "2020—7")) {
            JsonNode page = getJson(learner, "/api/v1/learning/questions", "sourceId", sourceId, "query", query);
            assertThat(ids(page)).as("结构化题号 %s", query).containsExactlyInAnyOrder(standard, legacy);
        }
    }

    @Test
    void defaultOrderIsSourceThenYearThenNaturalQuestionNumber() throws Exception {
        String first = published(sourceId, "single_choice", 2020, "1", 1, "第 1 题", List.of(new Relation(pointId, 0)), List.of());
        String second = published(sourceId, "single_choice", 2020, "2", 1, "第 2 题", List.of(new Relation(pointId, 0)), List.of());
        String seventh = published(sourceId, "single_choice", 2020, "7", 1, "第 7 题", List.of(new Relation(pointId, 0)), List.of());
        String tenth = published(sourceId, "single_choice", 2020, "10", 1, "第 10 题", List.of(new Relation(pointId, 0)), List.of());
        String twentySecond = published(sourceId, "single_choice", 2020, "22", 1, "第 22 题", List.of(new Relation(pointId, 0)), List.of());
        String legacySame = published(sourceId, "single_choice", 2020, "2020-7", 1, "历史第 7 题", List.of(new Relation(pointId, 0)), List.of());
        String nextYear = published(sourceId, "single_choice", 2021, "1", 1, "次年第一题", List.of(new Relation(pointId, 0)), List.of());
        String otherSource = published(otherSourceId, "single_choice", 2019, "1", 1, "别来源", List.of(new Relation(pointId, 0)), List.of());

        Cookie learner = register();
        List<String> actual = ids(getJson(learner, "/api/v1/learning/questions", "sourceId", sourceId));
        // 自然排序：1 < 2 < 7 < 10 < 22，绝不能出现 1, 10, 2。
        assertThat(actual.indexOf(first)).isLessThan(actual.indexOf(second));
        assertThat(actual.indexOf(second)).isLessThan(actual.indexOf(seventh));
        assertThat(actual.indexOf(seventh)).isLessThan(actual.indexOf(tenth));
        assertThat(actual.indexOf(tenth)).isLessThan(actual.indexOf(twentySecond));
        // 同一年份内 "7" 与 "2020-7" 是同一个题号，都排在次年之前。
        assertThat(actual.indexOf(seventh)).isLessThan(actual.indexOf(nextYear));
        assertThat(actual.indexOf(legacySame)).isLessThan(actual.indexOf(nextYear));
        // 排序稳定：完整顺序 = 年份升序 + 该年份内题号自然升序。
        assertThat(actual).containsExactlyInAnyOrder(first, second, seventh, tenth, twentySecond, legacySame, nextYear);
        assertThat(actual.get(6)).isEqualTo(nextYear);
        // 来源是第一个排序键：两个来源各自成组，互不交错。
        List<String> otherActual = ids(getJson(learner, "/api/v1/learning/questions", "sourceId", otherSourceId));
        assertThat(otherActual).containsExactly(otherSource);
    }

    @Test
    void paginationUsesFixedSizeAndDistinctTotal() throws Exception {
        for (int index = 1; index <= 25; index++) {
            published(sourceId, "single_choice", 2020, String.valueOf(index), 1, "分页题 " + index,
                    List.of(new Relation(pointId, 0)), List.of());
        }
        Cookie learner = register();
        JsonNode first = getJson(learner, "/api/v1/learning/questions", "sourceId", sourceId, "page", "0");
        assertThat(first.path("size").asInt()).isEqualTo(20);
        assertThat(first.path("totalElements").asInt()).isEqualTo(25);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("content").size()).isEqualTo(20);
        JsonNode second = getJson(learner, "/api/v1/learning/questions", "sourceId", sourceId, "page", "1");
        assertThat(second.path("content").size()).isEqualTo(5);
        assertThat(ids(second)).doesNotContainAnyElementsOf(ids(first));
    }

    @Test
    void facetsReportPublishedSourcesExistingYearsAndAllEnabledBooksInSortOrder() throws Exception {
        published(sourceId, "single_choice", 2020, "1", 1, "facets 题", List.of(new Relation(pointId, 0)),
                List.of(new Membership(bookId, chapterId)));
        // 只有草稿的来源不能出现在 facets 的 sources 里。
        String draftOnlySource = source("草稿来源-" + UUID.randomUUID().toString().substring(0, 8), "custom");
        String draft = question(draftOnlySource, "single_choice", 2031, "1", 1, "草稿", "draft");
        relate(draft, pointId, "core", 0);

        Cookie learner = register();
        JsonNode facets = getJson(learner, "/api/v1/learning/questions/facets");
        List<String> sourceNames = new ArrayList<>();
        facets.path("sources").forEach(item -> sourceNames.add(item.path("displayName").asText()));
        String sourceName = jdbc.queryForObject("SELECT display_name FROM question_source WHERE id=?", String.class, sourceId);
        String draftOnlyName = jdbc.queryForObject("SELECT display_name FROM question_source WHERE id=?", String.class, draftOnlySource);
        assertThat(sourceNames).contains(sourceName).doesNotContain(draftOnlyName);
        assertThat(sourceNames.stream().filter(name -> name.equals(sourceName)).count()).isEqualTo(1);

        List<Integer> years = new ArrayList<>();
        facets.path("examYears").forEach(item -> years.add(item.asInt()));
        assertThat(years).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(years).contains(2020);
        assertThat(years).doesNotContain(2031);

        // 全平台 enabled Books：不受 selected Books 限制，章节按 sort_order。
        String bookName = jdbc.queryForObject("SELECT name FROM question_bank WHERE id=?", String.class, bookId);
        String otherBookName = jdbc.queryForObject("SELECT name FROM question_bank WHERE id=?", String.class, otherBookId);
        List<String> bookNames = new ArrayList<>();
        facets.path("books").forEach(item -> bookNames.add(item.path("name").asText()));
        assertThat(bookNames).contains(bookName, otherBookName);
        JsonNode matched = null;
        for (JsonNode book : facets.path("books")) {
            if (bookName.equals(book.path("name").asText())) matched = book;
        }
        assertThat(matched).isNotNull();
        assertThat(matched.path("chapters").get(0).path("name").asText()).isEqualTo("第一章");
    }

    @Test
    void detailIsGlobalForPublishedFormalQuestionsAndDerivesAnswersFromOptions() throws Exception {
        // 单选三选项、正确答案 C：必须由 option.correct_option 派生，而不是 standard_answer_json。
        String objective = question(sourceId, "single_choice", 2020, "7", 1, "单选 $C$", "published");
        option(objective, "A", "甲", false, 0);
        option(objective, "B", "乙", false, 1);
        option(objective, "C", "丙", true, 2);
        relate(objective, pointId, "core", 0);
        String solution = published(sourceId, "solution", 2020, "8", 3, "综合题",
                List.of(new Relation(otherPointId, 0)), List.of());
        String draft = question(sourceId, "single_choice", 2020, "9", 1, "草稿", "draft");
        relate(draft, pointId, "core", 0);
        String child = question(sourceId, "single_choice", 2020, "10", 1, "子题", "published");
        jdbc.update("UPDATE question_resource SET parent_question_id=?, derivation_type='remedial_step' WHERE id=?",
                objective, child);
        relate(child, pointId, "core", 0);

        // 未选择任何文集：仍然可以看 published Formal 详情。
        Cookie learner = register();
        String learnerId = jdbc.queryForObject("SELECT id FROM learner_account WHERE username='directory_user'", String.class);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learnerId);

        JsonNode detail = getJson(learner, "/api/v1/learning/questions/" + objective);
        // 单选正确答案必须来自 option.correct_option，而不是 standard_answer_json。
        assertThat(detail.path("correctAnswer").asText()).isEqualTo("C");
        assertThat(detail.path("analysisMarkdown").asText()).isNotBlank();
        assertThat(detail.path("displayQuestionNumber").asText()).isEqualTo("7");
        assertThat(detail.path("knowledgePoints").size()).isEqualTo(1);
        assertThat(detail.path("examLabel").asText()).isEqualTo("2020年考研数学一真题");

        JsonNode solutionDetail = getJson(learner, "/api/v1/learning/questions/" + solution);
        assertThat(solutionDetail.path("correctAnswer").isNull()).isTrue();
        assertThat(solutionDetail.path("analysisMarkdown").asText()).isNotBlank();

        mvc.perform(get("/api/v1/learning/questions/{id}", draft).cookie(learner))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/learning/questions/{id}", child).cookie(learner))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/learning/questions/{id}", UUID.randomUUID().toString()).cookie(learner))
                .andExpect(status().isNotFound());
    }

    /** 浏览题目不创建 Attempt、不写 Evidence。 */
    @Test
    void browsingQuestionsDoesNotCreateAttemptsOrEvidence() throws Exception {
        String questionIdValue = published(sourceId, "single_choice", 2020, "7", 1,
                "浏览不产生 attempt", List.of(new Relation(pointId, 0)), List.of());
        Cookie learner = register();
        getJson(learner, "/api/v1/learning/questions", "sourceId", sourceId);
        getJson(learner, "/api/v1/learning/questions/" + questionIdValue);

        Integer attempts = jdbc.queryForObject("SELECT COUNT(*) FROM study_attempt", Integer.class);
        Integer evidence = jdbc.queryForObject("SELECT COUNT(*) FROM learner_knowledge_evidence", Integer.class);
        assertThat(attempts).isZero();
        assertThat(evidence).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private record Relation(String knowledgePointId, int sortOrder) {}
    private record Membership(String bookId, String chapterId) {}

    private List<String> ids(JsonNode page) {
        List<String> values = new ArrayList<>();
        page.path("content").forEach(item -> values.add(item.path("id").asText()));
        return values;
    }

    private JsonNode getJson(Cookie learner, String path, String... params) throws Exception {
        var request = get(path).cookie(learner);
        for (int index = 0; index < params.length; index += 2) request = request.param(params[index], params[index + 1]);
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private String source(String displayName, String sourceType) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_source(id, source_type, canonical_name, display_name, status, revision)
                VALUES (?, ?, ?, ?, 'active', 1)
                """, id, sourceType, "canonical-" + id, displayName);
        return id;
    }

    private String book(String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,'',TRUE,1,1)", id, name);
        return id;
    }

    private String chapter(String bookId, String code, String name, int sortOrder) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision)
                VALUES (?,?,?,?,'',?,1)
                """, id, bookId, code, name, sortOrder);
        return id;
    }

    private String knowledgePoint(String code, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','测试','测试','core','active','','',0,1)
                """, id, code, name);
        return id;
    }

    private String published(String sourceId, String questionType, Integer examYear, String questionNumber,
                             int difficulty, String content, List<Relation> relations, List<Membership> memberships) {
        String id = question(sourceId, questionType, examYear, questionNumber, difficulty, content, "published");
        if ("single_choice".equals(questionType)) {
            option(id, "A", "甲", false, 0);
            option(id, "B", "乙", true, 1);
        } else if ("multiple_choice".equals(questionType)) {
            option(id, "A", "甲", true, 0);
            option(id, "B", "乙", true, 1);
        } else if ("true_false".equals(questionType)) {
            option(id, "true", "正确", true, 0);
            option(id, "false", "错误", false, 1);
        }
        for (int index = 0; index < relations.size(); index++) {
            relate(id, relations.get(index).knowledgePointId(), index == 0 ? "core" : "auxiliary", index);
        }
        for (Membership membership : memberships) {
            jdbc.update("""
                    INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order)
                    VALUES (?,?,?,0)
                    """, membership.bookId(), relations.get(0).knowledgePointId(), membership.chapterId());
        }
        return id;
    }

    private String question(String sourceId, String questionType, Integer examYear, String questionNumber,
                            int difficulty, String content, String statusValue) {
        String id = UUID.randomUUID().toString();
        String presentation = "solution".equals(questionType) ? "self_assessment" : questionType;
        String grading = "solution".equals(questionType) ? "self_assessment" : "auto";
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_id,source_type,source_name,exam_year,question_number,
                    question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,
                    difficulty,status,revision)
                VALUES (?,'数学一',?,'real_exam','真题来源',?,?,?,?,?,?,NULL,'完整解析',?,?,1)
                """, id, sourceId, examYear, questionNumber, questionType, presentation, grading, content,
                difficulty, statusValue);
        return id;
    }

    private void relate(String questionId, String pointId, String role, int sortOrder) {
        jdbc.update("""
                INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order)
                VALUES (?,?,?,?)
                """, questionId, pointId, role, sortOrder);
    }

    private void option(String questionId, String key, String text, boolean correct, int sortOrder) {
        jdbc.update("""
                INSERT INTO question_resource_option(id,question_id,option_key,option_text,correct_option,sort_order)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), questionId, key, text, correct, sortOrder);
    }

    private Cookie register() throws Exception {
        var result = mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"directory_user\",\"displayName\":\"浏览者\",\"password\":\"password-123\"}"))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie(LearnerAuthService.COOKIE);
        if (cookie != null) return cookie;
        return mvc.perform(post("/api/v1/learner/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"directory_user\",\"password\":\"password-123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

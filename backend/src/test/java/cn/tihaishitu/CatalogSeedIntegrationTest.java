package cn.tihaishitu;

import cn.tihaishitu.catalog.CatalogService;
import cn.tihaishitu.catalog.LegacyCatalogMigrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;
import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog-seed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.catalog.seed-enabled=true"
})
@AutoConfigureMockMvc
class CatalogSeedIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CatalogService catalog;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LegacyCatalogMigrator migrator;
    @Autowired
    cn.tihaishitu.game.KnowledgeQuestionPoolService questionPool;

    @Test
    void legacyBanksAreIdempotentlyProjectedIntoGlobalResources() {
        int legacyQuestions = jdbc.queryForObject("SELECT COUNT(*) FROM question_item", Integer.class);
        int projected = jdbc.queryForObject("SELECT COUNT(*) FROM legacy_question_map", Integer.class);
        int bankItems = jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_item", Integer.class);
        org.assertj.core.api.Assertions.assertThat(projected).isEqualTo(legacyQuestions);
        org.assertj.core.api.Assertions.assertThat(bankItems).isEqualTo(legacyQuestions);

        migrator.migrateAfterSeed();
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM legacy_question_map", Integer.class))
                .isEqualTo(projected);
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_item", Integer.class))
                .isEqualTo(bankItems);
    }

    @Test
    void builtInCatalogIsSeededWithUuidKeysAndAvailableByManifest() throws Exception {
        String body = mvc.perform(get("/api/v1/bootstrap").cookie(register("seed_user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankManifest").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                .path("bankManifest").path(0).path("id").asText();
        UUID.fromString(id);
        String etag = mvc.perform(get("/api/v1/question-banks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.questions").isNotEmpty())
                .andExpect(jsonPath("$.knowledgePoints").isNotEmpty())
                .andReturn().getResponse().getHeader("ETag");
        mvc.perform(get("/api/v1/question-banks/{id}", id).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void activityAnswerUsesOnlyUuidAndCompactAnswerPayload() throws Exception {
        // 显式锁定到种子题库里的文集，避免同测试类新增的 legacy 文集（同进程共享 H2）影响选题。
        String seedBank = jdbc.queryForObject(
                "SELECT id FROM question_bank WHERE enabled = TRUE ORDER BY created_at, id LIMIT 1", String.class);
        String created = mvc.perform(post("/api/v1/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"折叶\",\"gender\":\"男\",\"bankIds\":[\"%s\"],\"weights\":{}}"
                                .formatted(seedBank)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String gameId = mapper.readTree(created).path("id").asText();
        String started = mvc.perform(post("/api/v1/games/{id}/activities", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attempt.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode game = mapper.readTree(started);
        String attemptId = game.path("attempt").path("id").asText();
        String questionId = game.path("attempt").path("question").path("id").asText();
        UUID.fromString(attemptId);
        UUID.fromString(questionId);
        JsonNode answer = catalog.findAll().stream().flatMap(bank -> bank.questions().stream())
                .filter(question -> question.id().equals(questionId)).findFirst().orElseThrow().answer();
        var body = mapper.createObjectNode();
        body.put("attemptId", attemptId); body.put("questionId", questionId); body.set("answer", answer);
        mvc.perform(post("/api/v1/games/{id}/answers", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt.result.correct").value(true))
                .andExpect(jsonPath("$.records[0].questionId").value(questionId))
                .andExpect(jsonPath("$.records[0].question").doesNotExist());
    }

    /**
     * V7.3 回归：Legacy `/games/**` 的 plannedRounds 必须由 Legacy 自己的 KnowledgePoint plan 决定，
     * 不能看 Book Question Pool 的题数。
     *
     * <p>这里刻意构造“Legacy plan 非空，但 candidatesForBooks = 0”的错配场景
     * （文集只通过 legacy_knowledge_map 归属知识点，没有 question_bank_knowledge 记录）。
     * 修复前 plannedRounds 会被压成 1，第 1 题答完就提前结算。</p>
     */
    @Test
    void legacyGameRoundsFollowItsOwnKnowledgePointPlanNotTheBookQuestionPool() throws Exception {
        String book = legacyBookWithPoints(5);
        String created = mvc.perform(post("/api/v1/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"折叶\",\"gender\":\"男\",\"bankIds\":[\"%s\"],\"weights\":{}}"
                                .formatted(book)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String gameId = mapper.readTree(created).path("id").asText();

        // 前置断言：Legacy 范畴有计划内的知识点，而 Book-level 题池对这个文集是空的。
        int planSize = questionPool.planKnowledgePoints(Set.of(book), 5).knowledgePointIds().size();
        assertThat(planSize).isEqualTo(5);
        assertThat(questionPool.candidatesForBooks(Set.of(book), Set.of())).isEmpty();

        String started = mvc.perform(post("/api/v1/games/{id}/activities", gameId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode run = mapper.readTree(started).path("adventure").path("run");
        // 修复前这里是 1（被 Book 题池的 0 压成 Math.max(1, 0)），于是第 1 题后提前结算。
        assertThat(run.path("plannedRounds").asInt()).isEqualTo(planSize);
        assertThat(run.path("knowledgePointIds")).hasSize(planSize);
        assertThat(run.path("definition").path("rounds").asInt())
                .as("read 活动声明的轮数必须与 Legacy plan 一致，测试才有意义")
                .isEqualTo(planSize);

        // 第 1 题答完仍然 active。
        String attempt = mapper.readTree(started).path("attempt").path("id").asText();
        String question = mapper.readTree(started).path("attempt").path("question").path("id").asText();
        String current = answerLegacy(gameId, attempt, question);
        JsonNode firstRun = mapper.readTree(current).path("adventure").path("run");
        assertThat(firstRun.path("status").asText()).isEqualTo("active");
        assertThat(firstRun.path("knowledgePointIndex").asInt()).isEqualTo(1);

        // 走完 planSize 轮后才结算，且 score 分母是 Legacy 自己的 planSize。
        for (int round = 2; round <= planSize; round++) {
            JsonNode game = mapper.readTree(current);
            String nextAttempt = game.path("attempt").path("id").asText();
            current = mvc.perform(post("/api/v1/games/{id}/next", gameId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"attemptId\":\"%s\"}".formatted(nextAttempt)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JsonNode attemptNode = mapper.readTree(current).path("attempt");
            current = answerLegacy(gameId, attemptNode.path("id").asText(),
                    attemptNode.path("question").path("id").asText());
        }
        JsonNode settled = mapper.readTree(current).path("adventure").path("run");
        assertThat(settled.path("status").asText()).isEqualTo("settled");
        assertThat(settled.path("score").asInt()).isEqualTo(100);
    }

    /** 只用 legacy_knowledge_map 归属 N 个知识点的文集：没有 question_bank_knowledge 记录。 */
    private String legacyBookWithPoints(int count) {
        String book = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'旧版藏书','',TRUE,1,1)", book);
        for (int index = 0; index < count; index++) {
            String point = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                        default_role,status,description,explanation,sort_order,revision)
                    VALUES (?,?,?,'测试','节','章','core','active','','',?,1)
                    """, point, "LEGACY-K" + index + "-" + point, "旧知识点" + index, index);
            jdbc.update("INSERT INTO legacy_knowledge_map(bank_id,legacy_id,global_id) VALUES (?,?,?)",
                    book, UUID.randomUUID().toString(), point);
            String question = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                        grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                    VALUES (?,'测试','custom','true_false','true_false','auto',?,'true','解析',2,'published',1)
                    """, question, "旧版题目" + index);
            // 正式题唯一答案事实是 option.correct_option，不再读取 standard_answer_json。
            QuestionFixtures.trueFalseOptions(jdbc, question);
            jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                    question, point);
        }
        return book;
    }

    private String answerLegacy(String gameId, String attemptId, String questionId) throws Exception {
        JsonNode standard = catalog.findAll().stream().flatMap(bank -> bank.questions().stream())
                .filter(question -> question.id().equals(questionId)).findFirst()
                .map(question -> question.answer())
                .orElse(mapper.getNodeFactory().booleanNode(true));
        var body = mapper.createObjectNode();
        body.put("attemptId", attemptId);
        body.put("questionId", questionId);
        body.set("answer", standard);
        return mvc.perform(post("/api/v1/games/{id}/answers", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"displayName\":\"测试\",\"password\":\"password-123\"}"))
                .andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

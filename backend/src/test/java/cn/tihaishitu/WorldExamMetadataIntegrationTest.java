package cn.tihaishitu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.tihaishitu.learning.AdaptiveStudyPlanner;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * World / 副本正式发题也必须带完整题面 metadata：
 *
 * <pre>
 * examMetadata → sourceName / examYear / questionNumber / displayQuestionNumber
 *                / examLabel / knowledgePoints[{id,name,role}]
 * </pre>
 *
 * 数据在发题时冻结进 attempt snapshot，刷新后不变；Hub Practice 与 World 共用
 * {@link cn.tihaishitu.learning.QuestionExamMetadataBuilder}，不允许两套规则。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:world-exam-metadata;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class WorldExamMetadataIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    /** 固定本轮 target，避免 shuffle 让断言不确定。 */
    @MockitoSpyBean AdaptiveStudyPlanner planner;

    @Test
    void worldAttemptSnapshotFreezesExamMetadataWithRoles() throws Exception {
        Fixture fixture = fixture();
        Cookie cookie = register("world-metadata");
        String learner = jdbc.queryForObject(
                "SELECT id FROM learner_account WHERE username='world-metadata'", String.class);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id=?", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)",
                learner, fixture.book());
        // 目标知识点只有这一道正式题，保证第一次 World 发题一定抽到它。
        jdbc.update("DELETE FROM learner_focus_knowledge WHERE learner_id=?", learner);

        // 本轮 target 固定为目标知识点，避免 shuffle 让"第一题是不是目标题"不确定。
        LinkedHashSet<String> allowed = new LinkedHashSet<>(List.of(fixture.target(), fixture.auxiliary()));
        doAnswer(invocation -> new AdaptiveStudyPlanner.AdaptiveStudyPlan(
                allowed, List.of(fixture.target()))).when(planner).randomPlan(anyString(), anySet(), anyInt());

        JsonNode game = json(mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf())
                        .cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterName\":\"学子\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(game).isNotNull();
        JsonNode began = json(mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf())
                        .cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":\"read\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        String attemptId = began.path("attempt").path("id").asText();
        JsonNode attempt = began.path("attempt");
        assertThat(attempt.path("question").path("id").asText()).isEqualTo(fixture.question());

        // 1) HTTP 返回的题面 metadata
        JsonNode metadata = attempt.path("question").path("examMetadata");
        assertThat(metadata.path("subjectName").asText()).isEqualTo("数学一");
        assertThat(metadata.path("sourceName").asText()).isEqualTo("2014年全国硕士研究生招生考试数学一");
        assertThat(metadata.path("examYear").asInt()).isEqualTo(2014);
        assertThat(metadata.path("questionNumber").asText()).isEqualTo("2014-1");
        assertThat(metadata.path("displayQuestionNumber").asText()).isEqualTo("1");
        assertThat(metadata.path("examLabel").asText()).isEqualTo("2014年考研数学一真题");
        assertThat(metadata.path("knowledgePoints")).hasSize(2);
        assertThat(java.util.List.of(metadata.path("knowledgePoints").get(0).path("role").asText(),
                        metadata.path("knowledgePoints").get(1).path("role").asText()))
                .containsExactlyInAnyOrder("core", "auxiliary");

        // 2) 冻结进 attempt snapshot，刷新后不变
        String snapshot = jdbc.queryForObject(
                "SELECT question_snapshot_json FROM study_attempt WHERE id=?", String.class, attemptId);
        JsonNode frozen = mapper.readTree(snapshot).path("examMetadata");
        assertThat(frozen).isEqualTo(metadata);
        assertThat(frozen.path("examLabel").asText()).isEqualTo("2014年考研数学一真题");
        assertThat(frozen.path("displayQuestionNumber").asText()).isEqualTo("1");
        assertThat(frozen.path("knowledgePoints")).hasSize(2);
    }

    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }

    private Cookie register(String username) throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"displayName\":\"题面\",\"password\":\"password-123\"}"
                                .formatted(username)))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(
                        cn.tihaishitu.learner.LearnerAuthService.COOKIE);
    }

    /**
     * 一本 selected Book，两个知识点：目标知识点带真题 metadata（core + auxiliary 标签），
     * auxiliary 只作为标签存在。本轮 target 由测试固定，所以 read 活动只需要 1 个 target。
     */
    private Fixture fixture() {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString(), auxiliary = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'世界题面文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)", chapter, book);
        point(target, "考点", "DRILL-WORLD", 0);
        point(auxiliary, "辅助考点", "DRILL-WORLD-AUX", 1);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, target, chapter);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,1)", book, auxiliary, chapter);
        String question = examQuestion(target, auxiliary);
        return new Fixture(book, target, auxiliary, question);
    }

    private void point(String id, String name, String code, int order) {
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,'数学一','节','章','core','active','','',?,1)
                """, id, code + "-" + id, name, order);
    }

    /** 目标知识点的真题：core = target，auxiliary = auxiliary，并带完整真题来源 metadata。 */
    private String examQuestion(String targetPoint, String auxiliaryPoint) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,source_name,question_number,
                    question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,
                    analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','real_exam','2014年全国硕士研究生招生考试数学一','2014-1',
                    'true_false','true_false','auto','世界题面','true','解析',2,'published',1)
                """, id);
        QuestionFixtures.trueFalseOptions(jdbc, id);
        jdbc.update("UPDATE question_resource SET exam_year=2014 WHERE id=?", id);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                id, targetPoint);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'auxiliary',1)",
                id, auxiliaryPoint);
        return id;
    }

    /** 填充知识点的普通正式题（无真题 metadata）。 */
    private void plainQuestion(String content, String point) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto',?,'true','解析',2,'published',1)
                """, id, content);
        QuestionFixtures.trueFalseOptions(jdbc, id);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",
                id, point);
    }

    private record Fixture(String book, String target, String auxiliary, String question) {}
}

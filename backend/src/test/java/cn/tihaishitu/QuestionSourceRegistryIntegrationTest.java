package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.learning.QuestionExamMetadataBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:source-registry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=source-admin",
        "app.initial-admin.password=source-admin-test-password"
})
class QuestionSourceRegistryIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired QuestionExamMetadataBuilder metadataBuilder;
    @Autowired PasswordEncoder encoder;

    @Test
    void adminManagesSourcesAndQuestionBindingUsesServerFacts() throws Exception {
        Cookie admin = login("source-admin", "source-admin-test-password");
        JsonNode created = json(mvc.perform(post("/api/v1/manage/sources").cookie(admin).with(csrf())
                        .contentType("application/json").content(source("real_exam", "正式名", "旧展示名", "active", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andReturn().getResponse().getContentAsString());
        String sourceId = created.path("id").asText();

        mvc.perform(post("/api/v1/manage/sources").cookie(admin).with(csrf()).contentType("application/json")
                        .content(source("real_exam", "正式名", "重复", "active", null)))
                .andExpect(status().isConflict());
        mvc.perform(put("/api/v1/manage/sources/{id}", sourceId).cookie(admin).with(csrf()).contentType("application/json")
                        .content(source("real_exam", "正式名", "并发", "active", 0L)))
                .andExpect(status().isConflict());

        String pointId = jdbc.queryForObject("SELECT id FROM global_knowledge_point WHERE status='active' ORDER BY code LIMIT 1", String.class);
        JsonNode question = json(mvc.perform(post("/api/v1/manage/questions").cookie(admin).with(csrf())
                        .contentType("application/json").content(question(sourceId, pointId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceId").value(sourceId))
                .andExpect(jsonPath("$.sourceType").value("real_exam"))
                .andExpect(jsonPath("$.sourceCanonicalName").value("正式名"))
                .andExpect(jsonPath("$.sourceName").value("旧展示名"))
                .andReturn().getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT source_name FROM question_resource WHERE id=?", String.class,
                question.path("id").asText())).isEqualTo("正式名");

        mvc.perform(put("/api/v1/manage/sources/{id}", sourceId).cookie(admin).with(csrf()).contentType("application/json")
                        .content(source("real_exam", "正式名", "新展示名", "disabled", 1L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        assertThat(metadataBuilder.build(question.path("id").asText(), null).path("sourceName").asText())
                .isEqualTo("新展示名");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE entity_id=? AND action_name='SOURCE_UPDATED'",
                Integer.class, sourceId)).isOne();

        mvc.perform(post("/api/v1/manage/questions").cookie(admin).with(csrf()).contentType("application/json")
                        .content(question(sourceId, pointId))).andExpect(status().isBadRequest());
    }

    @Test
    void nonAdminCannotManageSourcesAndMetadataSnapshotDoesNotMutate() throws Exception {
        String learner = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,'plain','普通',?,'active',1)", learner, encoder.encode("plain-test-password"));
        Cookie admin = login("source-admin", "source-admin-test-password");
        mvc.perform(get("/api/v1/manage/sources").cookie(login("plain", "plain-test-password")))
                .andExpect(status().isForbidden());
        jdbc.update("INSERT INTO learner_account_role(learner_id,role_name) VALUES (?,'CONTRIBUTOR')", learner);
        Cookie contributor = login("plain", "plain-test-password");
        mvc.perform(get("/api/v1/manage/sources").cookie(contributor)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/manage/sources").cookie(contributor).with(csrf()).contentType("application/json")
                        .content(source("custom", "无权限来源", "无权限来源", "active", null)))
                .andExpect(status().isForbidden());

        JsonNode created = json(mvc.perform(post("/api/v1/manage/sources").cookie(admin).with(csrf())
                        .contentType("application/json").content(source("mock", "快照源", "快照旧名", "active", null)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String questionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_resource(id,subject_name,source_id,source_type,source_name,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision) VALUES (?,'测试',?,'mock','快照源','true_false','true_false','auto','题','true','解析',1,'published',1)", questionId, created.path("id").asText());
        JsonNode frozen = metadataBuilder.build(questionId, null).deepCopy();
        mvc.perform(put("/api/v1/manage/sources/{id}", created.path("id").asText()).cookie(admin).with(csrf())
                        .contentType("application/json").content(source("mock", "快照源", "快照新名", "active", 1L)))
                .andExpect(status().isOk());
        assertThat(frozen.path("sourceName").asText()).isEqualTo("快照旧名");
        assertThat(metadataBuilder.build(questionId, null).path("sourceName").asText()).isEqualTo("快照新名");
    }

    private String source(String type, String canonical, String display, String status, Long revision) throws Exception {
        var value = new java.util.LinkedHashMap<String,Object>(); value.put("sourceType",type); value.put("canonicalName",canonical);
        value.put("displayName",display); value.put("status",status); if(revision!=null)value.put("expectedRevision",revision);
        return mapper.writeValueAsString(value);
    }
    private String question(String sourceId, String pointId) throws Exception {
        return mapper.writeValueAsString(Map.ofEntries(Map.entry("subject","测试"),Map.entry("sourceId",sourceId),
                Map.entry("sourceType","custom"),Map.entry("sourceName","伪造"),Map.entry("examYear",2026),Map.entry("questionNumber","1"),
                Map.entry("questionType","true_false"),Map.entry("presentationType","true_false"),Map.entry("gradingMode","auto"),
                Map.entry("content","题"),Map.entry("standardAnswer",true),Map.entry("analysis","解析"),Map.entry("difficulty",1),
                Map.entry("options",java.util.List.of(
                        Map.of("key","true","text","正确","correct",true,"sortOrder",0),
                        Map.of("key","false","text","错误","correct",false,"sortOrder",1))),
                Map.entry("knowledgePoints",java.util.List.of(Map.of("knowledgePointId",pointId,"role","core","sortOrder",0)))));
    }
    private Cookie login(String username,String password) throws Exception { return mvc.perform(post("/api/v1/learner/auth/login").with(csrf())
            .contentType("application/json").content(mapper.writeValueAsString(Map.of("username",username,"password",password))))
            .andExpect(status().isOk()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE); }
    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
}

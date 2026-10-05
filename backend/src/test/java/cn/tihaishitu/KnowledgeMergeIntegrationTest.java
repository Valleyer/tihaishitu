package cn.tihaishitu;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        "spring.datasource.url=jdbc:h2:mem:knowledge-merge;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=merge-admin",
        "app.initial-admin.password=merge-admin-test-password"
})
class KnowledgeMergeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;

    private String sourceId;
    private String targetId;
    private String bankId;
    private String secondBankId;

    @BeforeEach
    void fixture() {
        jdbc.update("DELETE FROM content_audit_log");
        jdbc.update("DELETE FROM knowledge_merge_history");
        sourceId = UUID.randomUUID().toString();
        targetId = UUID.randomUUID().toString();
        bankId = UUID.randomUUID().toString();
        secondBankId = UUID.randomUUID().toString();
        insertKnowledge(sourceId, "TEST-MERGE-SOURCE", "旧极值判定", "旧极值");
        insertKnowledge(targetId, "TEST-MERGE-TARGET", "无条件极值的极值点判定", "极值点判定");
        jdbc.update("INSERT INTO question_bank(id, name, description, enabled, weight_value, revision) VALUES (?, ?, '', TRUE, 1, 1)",
                bankId, "合并测试文集");
        jdbc.update("INSERT INTO question_bank(id, name, description, enabled, weight_value, revision) VALUES (?, ?, '', TRUE, 1, 1)",
                secondBankId, "第二本合并测试文集");
        String firstChapter = insertChapter(bankId, "MERGE-01");
        String secondChapter = insertChapter(secondBankId, "MERGE-02");
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id, knowledge_point_id, chapter_id, sort_order) VALUES (?, ?, ?, 0)",
                bankId, sourceId, firstChapter);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id, knowledge_point_id, chapter_id, sort_order) VALUES (?, ?, ?, 1)",
                bankId, targetId, firstChapter);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id, knowledge_point_id, chapter_id, sort_order) VALUES (?, ?, ?, 0)",
                secondBankId, sourceId, secondChapter);
        String migrateQuestion = insertQuestion("迁移关系题");
        String collapseQuestion = insertQuestion("折叠关系题");
        relation(migrateQuestion, sourceId, "auxiliary", 1);
        relation(collapseQuestion, sourceId, "core", 0);
        relation(collapseQuestion, targetId, "auxiliary", 2);
        jdbc.update("INSERT INTO question_bank_item(bank_id, question_id, sort_order) VALUES (?, ?, 0)", bankId, migrateQuestion);
        jdbc.update("INSERT INTO question_bank_item(bank_id, question_id, sort_order) VALUES (?, ?, 1)", bankId, collapseQuestion);
        ensureReviewer();
    }

    @Test
    void mergeMigratesAndCollapsesRelationsWithoutDeletingSource() throws Exception {
        Cookie admin = login("merge-admin", "merge-admin-test-password");
        Cookie reviewer = login("merge-reviewer", "merge-reviewer-test-password");
        String request = mapper.writeValueAsString(Map.of(
                "targetId", targetId, "reason", "细分口径重复，统一到正式知识点", "expectedRevision", 1));

        mvc.perform(post("/api/v1/manage/knowledge-points/{id}/merge", sourceId)
                        .cookie(reviewer).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/manage/knowledge-points/{id}/merge", sourceId)
                        .cookie(admin).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migratedRelations").value(1))
                .andExpect(jsonPath("$.collapsedRelations").value(1))
                .andExpect(jsonPath("$.affectedQuestions").value(2))
                .andExpect(jsonPath("$.source.status").value("deprecated"))
                .andExpect(jsonPath("$.source.mergedIntoId").value(targetId))
                .andExpect(jsonPath("$.target.questionCount").value(2));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global_knowledge_point WHERE id = ?", Integer.class, sourceId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_resource_knowledge WHERE knowledge_point_id = ?", Integer.class, sourceId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_resource_knowledge WHERE knowledge_point_id = ?", Integer.class, targetId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_resource_knowledge WHERE knowledge_point_id = ? AND relation_role = 'core'", Integer.class, targetId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision FROM question_bank WHERE id = ?", Long.class, bankId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT revision FROM question_bank WHERE id = ?", Long.class, secondBankId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_knowledge WHERE knowledge_point_id = ?", Integer.class, sourceId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_bank_knowledge WHERE knowledge_point_id = ?", Integer.class, targetId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_merge_history WHERE source_knowledge_id = ? AND target_knowledge_id = ?", Integer.class, sourceId, targetId))
                .isEqualTo(1);

        mvc.perform(get("/api/v1/manage/knowledge-points").cookie(admin)
                        .param("query", "TEST-MERGE-SOURCE").param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(targetId));
        mvc.perform(get("/api/v1/manage/audit-logs").cookie(reviewer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/manage/audit-logs").cookie(admin).param("action", "KNOWLEDGE_MERGED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].entityId").value(sourceId))
                .andExpect(jsonPath("$.content[0].metadata.targetId").value(targetId))
                .andExpect(jsonPath("$.content[0].metadata.migratedBookMemberships").value(1))
                .andExpect(jsonPath("$.content[0].metadata.collapsedBookMemberships").value(1));

        mvc.perform(post("/api/v1/manage/knowledge-points/{id}/merge", sourceId)
                        .cookie(admin).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isBadRequest());
    }

    private void insertKnowledge(String id, String code, String name, String alias) {
        jdbc.update("""
                INSERT INTO global_knowledge_point(
                    id, code, name, subject_name, section_name, chapter_name, default_role, status,
                    description, explanation, introduced_version, sort_order, revision)
                VALUES (?, ?, ?, '数学一', '高等数学', '多元函数微分学', 'core', 'active', '', '', 'test', 999, 1)
                """, id, code, name);
        jdbc.update("INSERT INTO knowledge_alias(id, knowledge_point_id, alias) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(), id, alias);
    }

    private String insertQuestion(String content) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(
                    id, subject_name, source_type, source_name, question_type, presentation_type,
                    grading_mode, content_markdown, standard_answer_json, analysis_markdown,
                    difficulty, status, revision)
                VALUES (?, '数学一', 'custom', '合并测试', 'true_false', 'true_false', 'auto', ?, 'true', '', 1, 'published', 1)
                """, id, content);
        return id;
    }

    private String insertChapter(String ownerBankId, String code) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_bank_chapter(
                    id, bank_id, chapter_code, name, description, sort_order)
                VALUES (?, ?, ?, '合并测试章节', '', 0)
                """, id, ownerBankId, code);
        return id;
    }

    private void relation(String questionId, String knowledgeId, String role, int sortOrder) {
        jdbc.update("""
                INSERT INTO question_resource_knowledge(question_id, knowledge_point_id, relation_role, sort_order)
                VALUES (?, ?, ?, ?)
                """, questionId, knowledgeId, role, sortOrder);
    }

    private void ensureReviewer() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM learner_account WHERE username = 'merge-reviewer'", Integer.class);
        if (count != null && count > 0) return;
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id, username, display_name, password_hash, status) VALUES (?, 'merge-reviewer', '合并审核员', ?, 'active')",
                id, encoder.encode("merge-reviewer-test-password"));
        jdbc.update("INSERT INTO learner_account_role(learner_id, role_name) VALUES (?, 'REVIEWER')", id);
    }

    private Cookie login(String username, String password) throws Exception {
        var result = mvc.perform(post("/api/v1/manage/auth/login").with(csrf())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

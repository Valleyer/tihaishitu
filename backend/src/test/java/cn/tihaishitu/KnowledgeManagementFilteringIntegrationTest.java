package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
        "spring.datasource.url=jdbc:h2:mem:knowledge-management-filter;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=knowledge-admin",
        "app.initial-admin.password=knowledge-admin-test-password"
})
class KnowledgeManagementFilteringIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void filtersAndPagesKnowledgeAndOnlyDeletesTrueOrphans() throws Exception {
        Cookie admin = login();
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'408','',TRUE,100,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'DS','数据结构','',0,1)", chapter, book);
        String assigned = point("408", "已归属知识点");
        String orphan = point("408", "真正孤儿");
        String questionBound = point("408", "仍有题目");
        String learned = point("408", "已有学习历史");
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, assigned, chapter);
        String question = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'408','custom','true_false','true_false','auto','测试','true','',1,'published',1)
                """, question);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", question, questionBound);
        String learner = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,'knowledge-history','学习者','x','active',1)", learner);
        jdbc.update("""
                INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,
                    target_difficulty,evidence_count,correct_streak,wrong_streak,model_version,revision)
                VALUES (?,?,50,3,2,1,0,1,'v1',1)
                """, learner, learned);

        mvc.perform(get("/api/v1/manage/knowledge-points/facets").cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subjects").isArray());
        mvc.perform(get("/api/v1/manage/knowledge-points").cookie(admin).param("subject", "408").param("bookId", book))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(assigned));
        mvc.perform(get("/api/v1/manage/knowledge-points").cookie(admin).param("subject", "408").param("chapterId", chapter))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/manage/knowledge-points").cookie(admin).param("subject", "408").param("membership", "unassigned"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.size").value(20));
        mvc.perform(get("/api/v1/manage/questions").cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20));
        mvc.perform(get("/api/v1/manage/audit-logs").cookie(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20));

        for (int index = 0; index < 51; index++) point("分页测试", "分页知识点" + index);
        mvc.perform(get("/api/v1/manage/knowledge-points").cookie(admin)
                        .param("subject", "分页测试").param("page", "1").param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(51))
                .andExpect(jsonPath("$.content.length()").value(1));

        mvc.perform(post("/api/v1/manage/knowledge-points/bulk-delete").cookie(admin).with(csrf())
                        .contentType("application/json")
                        .content("{\"ids\":[\"%s\",\"%s\",\"%s\"]}".formatted(orphan, questionBound, learned)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(1))
                .andExpect(jsonPath("$.blocked.length()").value(2));
        assertThat(count("global_knowledge_point", "id", orphan)).isZero();
        assertThat(count("global_knowledge_point", "id", questionBound)).isOne();
        assertThat(count("global_knowledge_point", "id", learned)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE action_name='KNOWLEDGE_DELETED' AND entity_id=?", Integer.class, orphan)).isOne();
    }

    private String point(String subject, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,?,?,?,'测试','测试','core','active','','',0,1)
                """, id, "K-" + id, name, subject);
        return id;
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/v1/manage/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"knowledge-admin\",\"password\":\"knowledge-admin-test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private int count(String table, String column, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, id);
    }
}

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:book-management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.initial-admin.username=book-admin",
        "app.initial-admin.password=book-admin-test-password"
})
class BookManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deletingBookOnlyRemovesOrganizationAndSelectedScope() throws Exception {
        String book = UUID.randomUUID().toString(), chapter = UUID.randomUUID().toString();
        String point = UUID.randomUUID().toString(), question = UUID.randomUUID().toString();
        String learner = UUID.randomUUID().toString(), attempt = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'待删除文集','',TRUE,1,1)", book);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'STABLE','稳定章节','',0,1)", chapter, book);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                    default_role,status,description,explanation,sort_order,revision)
                VALUES (?,'BOOK-KEEP','保留知识点','数学一','测试','测试','core','active','','',0,1)
                """, point);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", book, point, chapter);
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,
                    grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'数学一','custom','true_false','true_false','auto','保留题目','true','解析',1,'published',1)
                """, question);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", question, point);
        jdbc.update("INSERT INTO learner_account(id,username,display_name,password_hash,status,revision) VALUES (?,'book-learner','学习者','x','active',1)", learner);
        jdbc.update("INSERT INTO learner_study_profile(learner_id,pace,difficulty,focus_mode,revision) VALUES (?,'normal','standard','auto',1)", learner);
        jdbc.update("INSERT INTO learner_selected_book(learner_id,bank_id,weight_value) VALUES (?,?,100)", learner, book);
        jdbc.update("""
                INSERT INTO learner_knowledge_state(learner_id,knowledge_point_id,mastery_score,stability_days,
                    target_difficulty,evidence_count,correct_streak,wrong_streak,model_version,revision)
                VALUES (?,?,70,10,3,1,1,0,'v1',1)
                """, learner, point);
        jdbc.update("""
                INSERT INTO study_attempt(id,game_id,learner_id,question_id,question_snapshot_json,
                    standard_answer_json,status,grading_mode,target_knowledge_point_id,evidence_mode,question_difficulty)
                VALUES (?,NULL,?,?,'{}','true','graded','auto',?,'normal',1)
                """, attempt, learner, question, point);

        mvc.perform(delete("/api/v1/manage/books/{id}", book).cookie(login()).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(count("question_bank", "id", book)).isZero();
        assertThat(count("question_bank_chapter", "id", chapter)).isZero();
        assertThat(count("question_bank_knowledge", "knowledge_point_id", point)).isZero();
        assertThat(count("learner_selected_book", "learner_id", learner)).isZero();
        assertThat(count("global_knowledge_point", "id", point)).isOne();
        assertThat(count("question_resource", "id", question)).isOne();
        assertThat(count("learner_knowledge_state", "learner_id", learner)).isOne();
        assertThat(count("study_attempt", "id", attempt)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM content_audit_log WHERE action_name='BOOK_DELETED' AND entity_id=?", Integer.class, book)).isOne();
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/api/v1/manage/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"book-admin\",\"password\":\"book-admin-test-password\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }

    private int count(String table, String column, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, id);
    }
}

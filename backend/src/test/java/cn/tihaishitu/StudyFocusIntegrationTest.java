package cn.tihaishitu;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.learner.LearnerAuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:study-focus;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class StudyFocusIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired KnowledgeQuestionPoolService pool;

    @Test void selectedBooksBoundScopeAndRemovingBookCleansOldFocus() throws Exception {
        String firstPoint = knowledge("重点一"), secondPoint = knowledge("重点二");
        String firstBook = book("甲卷", firstPoint), secondBook = book("乙卷", secondPoint);
        question(firstPoint); question(secondPoint);
        Cookie learner = register();
        String learnerId = jdbc.queryForObject("SELECT id FROM learner_account WHERE username = 'focus_user'", String.class);
        jdbc.update("DELETE FROM learner_selected_book WHERE learner_id = ?", learnerId);

        mvc.perform(put("/api/v1/learner/study-profile").with(csrf()).cookie(learner).contentType(MediaType.APPLICATION_JSON)
                .content(profile(1, List.of(firstBook, secondBook), List.of(secondPoint))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.focusedKnowledgePoints[0].id").value(secondPoint));
        mvc.perform(put("/api/v1/learner/study-profile").with(csrf()).cookie(learner).contentType(MediaType.APPLICATION_JSON)
                .content(profile(2, List.of(firstBook), List.of(secondPoint))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.focusedKnowledgePoints").isEmpty());

        var plan = pool.planKnowledgePoints(Set.of(firstBook, secondBook), List.of(secondPoint), 2);
        assertThat(plan.allowedKnowledgePointIds()).containsExactlyInAnyOrder(firstPoint, secondPoint);
        assertThat(plan.knowledgePointIds()).startsWith(secondPoint).doesNotHaveDuplicates();
    }

    private String profile(long revision, List<String> books, List<String> focus) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of(
                "pace", "normal", "difficulty", "standard", "focusMode", "manual", "expectedRevision", revision,
                "selectedBookIds", books, "focusedKnowledgePointIds", focus));
    }
    private Cookie register() throws Exception { return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"focus_user\",\"displayName\":\"专注者\",\"password\":\"password-123\"}"))
            .andReturn().getResponse().getCookie(LearnerAuthService.COOKIE); }
    private String knowledge(String name) { String id=UUID.randomUUID().toString(); jdbc.update("""
            INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,default_role,status,description,explanation,sort_order,revision)
            VALUES (?,? ,?,'测试','分部','章节','core','active','','',0,1)
            """,id,"T-"+id,name); return id; }
    private String book(String name,String point) { String id=UUID.randomUUID().toString(), chapter=UUID.randomUUID().toString(); jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,?,'',TRUE,1,1)",id,name); jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C','章','',0,1)",chapter,id); jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)",id,point,chapter); return id; }
    private void question(String point) { String id=UUID.randomUUID().toString(); jdbc.update("""
            INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
            VALUES (?,'测试','custom','true_false','true_false','auto','题目','true','解析',1,'published',1)
            """,id); jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)",id,point); }
}

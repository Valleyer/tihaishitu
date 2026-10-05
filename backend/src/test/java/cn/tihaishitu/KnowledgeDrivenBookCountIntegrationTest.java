package cn.tihaishitu;

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

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:knowledge-book-count;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class KnowledgeDrivenBookCountIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void manifestsAndLearningHubCountPublishedQuestionsThroughKnowledgeScope() throws Exception {
        String bookId = UUID.randomUUID().toString();
        String chapterId = UUID.randomUUID().toString();
        String pointId = UUID.randomUUID().toString();
        String unavailablePointId = UUID.randomUUID().toString();
        String questionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO question_bank(id,name,description,enabled,weight_value,revision) VALUES (?,'知识驱动卷','',TRUE,1,1)", bookId);
        jdbc.update("INSERT INTO question_bank_chapter(id,bank_id,chapter_code,name,description,sort_order,revision) VALUES (?,?,'C1','第一章','',0,1)", chapterId, bookId);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                                                   default_role,status,description,explanation,sort_order,revision)
                VALUES (?,'COUNT-K1','知识点一','测试','分部','章节','core','active','','',0,1)
                """, pointId);
        jdbc.update("""
                INSERT INTO global_knowledge_point(id,code,name,subject_name,section_name,chapter_name,
                                                   default_role,status,description,explanation,sort_order,revision)
                VALUES (?,'COUNT-K2','没有正式题目的知识点','测试','分部','章节','core','active','','',1,1)
                """, unavailablePointId);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,0)", bookId, pointId, chapterId);
        jdbc.update("INSERT INTO question_bank_knowledge(bank_id,knowledge_point_id,chapter_id,sort_order) VALUES (?,?,?,1)", bookId, unavailablePointId, chapterId);
        jdbc.update("""
                INSERT INTO question_resource(id,subject_name,source_type,question_type,presentation_type,grading_mode,
                                              content_markdown,standard_answer_json,analysis_markdown,difficulty,status,revision)
                VALUES (?,'测试','custom','true_false','true_false','auto','题目','true','解析',1,'published',1)
                """, questionId);
        jdbc.update("INSERT INTO question_resource_knowledge(question_id,knowledge_point_id,relation_role,sort_order) VALUES (?,?,'core',0)", questionId, pointId);

        Cookie learner = register();
        mvc.perform(get("/api/v1/bootstrap").cookie(learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankManifest[?(@.id == '%s')].questionCount".formatted(bookId)).value(1))
                .andExpect(jsonPath("$.bankManifest[?(@.id == '%s')].knowledgePointCount".formatted(bookId)).value(1));
        mvc.perform(get("/api/v1/learning/books").cookie(learner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].questionCount".formatted(bookId)).value(1))
                .andExpect(jsonPath("$[?(@.id == '%s')].knowledgePointCount".formatted(bookId)).value(1));
        mvc.perform(get("/api/v1/learning/knowledge-points").cookie(learner)
                        .param("bookId", bookId).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pointId))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
        mvc.perform(get("/api/v1/learning/knowledge-points/{id}", unavailablePointId).cookie(learner))
                .andExpect(status().isNotFound());
        Integer oldLinks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_bank_item WHERE bank_id = ? AND question_id = ?",
                Integer.class, bookId, questionId);
        org.assertj.core.api.Assertions.assertThat(oldLinks).isZero();
    }

    private Cookie register() throws Exception {
        return mvc.perform(post("/api/v1/learner/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"book_count_user\",\"displayName\":\"测试\",\"password\":\"password-123\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie(LearnerAuthService.COOKIE);
    }
}

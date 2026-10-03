package cn.tihaishitu;

import cn.tihaishitu.game.GameStore;
import cn.tihaishitu.game.QuestionAttemptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:history-question-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class HistoryQuestionRecoveryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired GameStore games;
    @Autowired QuestionAttemptStore attempts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void forgedImportedAndUnfinishedAttemptsDoNotExposeGlobalAnswers() throws Exception {
        String firstGame = game();
        String secondGame = game();
        String questionId = globalQuestion("不可提前查看的当前题面");

        String forgedAttempt = UUID.randomUUID().toString();
        addRecord(firstGame, forgedAttempt, questionId);

        String activeAttempt = UUID.randomUUID().toString();
        attempts.create(activeAttempt, firstGame, questionId,
                questionSnapshot(questionId, "active"), TextNode.valueOf("A"));
        addRecord(firstGame, activeAttempt, questionId);

        String revealedAttempt = UUID.randomUUID().toString();
        attempts.create(revealedAttempt, firstGame, questionId,
                questionSnapshot(questionId, "revealed"), TextNode.valueOf("A"), "self_assessment");
        attempts.reveal(attempts.find(revealedAttempt, firstGame));
        addRecord(firstGame, revealedAttempt, questionId);

        String otherAttempt = UUID.randomUUID().toString();
        attempts.create(otherAttempt, secondGame, questionId,
                questionSnapshot(questionId, "other"), TextNode.valueOf("A"));
        attempts.recordAnswer(attempts.find(otherAttempt, secondGame), TextNode.valueOf("A"), true);
        addRecord(secondGame, otherAttempt, questionId);

        ObjectNode body = mapper.createObjectNode();
        body.putArray("attemptIds")
                .add(forgedAttempt).add(activeAttempt).add(revealedAttempt).add(otherAttempt);
        mvc.perform(post("/api/v1/games/{id}/history/questions", firstGame)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions").isEmpty());
    }

    @Test
    void gradedSnapshotWinsOverCurrentGlobalQuestionVersion() throws Exception {
        String gameId = game();
        String questionId = globalQuestion("当前版本题面");
        String gradedAttemptId = UUID.randomUUID().toString();
        ObjectNode historical = questionSnapshot(questionId, "作答时的历史题面");
        attempts.create(gradedAttemptId, gameId, questionId, historical, TextNode.valueOf("A"));
        attempts.recordAnswer(attempts.find(gradedAttemptId, gameId), TextNode.valueOf("A"), true);
        addRecord(gameId, gradedAttemptId, questionId);

        ObjectNode body = mapper.createObjectNode();
        body.putArray("attemptIds").add(gradedAttemptId);
        mvc.perform(post("/api/v1/games/{id}/history/questions", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andExpect(jsonPath("$.questions[0].attemptId").value(gradedAttemptId))
                .andExpect(jsonPath("$.questions[0].question.question").value("作答时的历史题面"))
                .andExpect(jsonPath("$.questions[0].question.answer").value("A"));

        Long links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_bank_item WHERE question_id = ?", Long.class, questionId);
        assertThat(links).isZero();
    }

    private String game() throws Exception {
        String response = mvc.perform(post("/api/v1/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"折叶\",\"gender\":\"男\",\"bankIds\":[],\"weights\":{}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("id").asText();
    }

    private void addRecord(String gameId, String attemptId, String questionId) {
        ObjectNode game = games.findObject(gameId);
        ObjectNode record = game.withArray("records").addObject();
        record.put("attemptId", attemptId);
        record.put("questionId", questionId);
        record.put("answer", "A");
        record.put("correct", true);
        record.put("at", Instant.now().toString());
        record.put("review", false);
        games.save(game);
    }

    private String globalQuestion(String content) {
        String pointId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO global_knowledge_point(
                    id, code, name, subject_name, section_name, chapter_name, default_role,
                    status, description, explanation, introduced_version, sort_order, revision)
                VALUES (?, ?, '历史恢复知识点', '测试科目', '测试分部', '测试章节', 'core',
                        'active', '', '', 'pr8-test', 0, 1)
                """, pointId, "HISTORY-" + pointId);
        String questionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO question_resource(
                    id, subject_name, source_type, source_name, question_type, presentation_type,
                    grading_mode, content_markdown, standard_answer_json, analysis_markdown,
                    difficulty, status, revision)
                VALUES (?, '测试科目', 'custom', '历史恢复测试', 'single_choice', 'single_choice',
                        'auto', ?, '"A"', '当前解析', 2, 'archived', 1)
                """, questionId, content);
        jdbc.update("""
                INSERT INTO question_resource_option(
                    id, question_id, option_key, option_text, correct_option, sort_order)
                VALUES (?, ?, 'A', '正确选项', TRUE, 0)
                """, UUID.randomUUID().toString(), questionId);
        jdbc.update("""
                INSERT INTO question_resource_knowledge(
                    question_id, knowledge_point_id, relation_role, sort_order)
                VALUES (?, ?, 'core', 0)
                """, questionId, pointId);
        return questionId;
    }

    private ObjectNode questionSnapshot(String questionId, String content) {
        ObjectNode question = mapper.createObjectNode();
        question.put("id", questionId);
        question.put("subject", "测试科目");
        question.put("category", "测试分部");
        question.put("chapter", "测试章节");
        question.put("type", "single_choice");
        question.put("originalType", "single_choice");
        question.put("presentationType", "single_choice");
        question.put("gradingMode", "auto");
        question.put("question", content);
        question.putObject("options").put("A", "正确选项");
        question.put("answer", "A");
        question.putArray("aliases");
        question.putArray("keywords");
        question.put("explanation", "历史解析");
        question.put("difficulty", 2);
        question.put("frequency", 3);
        question.putArray("tags");
        question.putArray("knowledgePointIds");
        question.put("enabled", true);
        return question;
    }
}

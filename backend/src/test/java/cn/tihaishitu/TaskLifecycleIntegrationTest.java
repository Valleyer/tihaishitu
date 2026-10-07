package cn.tihaishitu;

import cn.tihaishitu.world.WorldRegistry;
import cn.tihaishitu.world.WorldStateStore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:task-lifecycle;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class TaskLifecycleIntegrationTest extends DiagnosticWorldTestSupport {
    @Autowired WorldStateStore worldStates;

    @Test
    void mainAndSideTasksRetryWithoutPenaltyThenClosePermanentlyOnCompletion() throws Exception {
        ExamScenario scenario = examScenario();
        // PR3 起 RANDOM 在同一 Learner × 同一 Asia/Shanghai 业务日内同一道 Question 只能出一次，
        // 而本测试要跑 county-exam-paper（10 题 × 2 轮）+ story-letter（5 题 × 2 轮）= 30 道正式题。
        // 因此先把试卷文集补到 40 道，避免第二个 Phase 因为当天额度耗尽而无法开始。
        for (int extra = 0; extra < 2; extra++)
            for (String point : scenario.points()) question(2, java.util.List.of(relation(point, "core")));
        Cookie cookie = register("task-lifecycle");
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class,
                "task-lifecycle");
        jdbc.update("UPDATE learner_study_profile SET focus_mode='manual' WHERE learner_id=?", learner);
        for (int index = 0; index < scenario.points().size(); index++)
            jdbc.update("INSERT INTO learner_focus_knowledge(learner_id,knowledge_point_id,sort_order) VALUES (?,?,?)",
                    learner, scenario.points().get(index), index);
        initialize(cookie);

        var state = worldStates.find(learner, WorldRegistry.ANCIENT_OFFICIAL);
        state.with("adventure").put("locationId", "exam-street");
        state.with("adventure").with("exams").with("county-exam").put("status", "registered");
        int initialCoins = state.path("player").path("coins").asInt();
        worldStates.save(learner, WorldRegistry.ANCIENT_OFFICIAL, state);

        JsonNode mainFailed = complete(begin(cookie, "county-exam-paper"), cookie, 1);
        assertThat(mainFailed.path("adventure").path("run").path("score").asInt()).isEqualTo(90);
        assertThat(mainFailed.path("adventure").path("run").path("rewards").isEmpty()).isTrue();
        assertThat(mainFailed.path("adventure").path("run").path("costRefunded").asBoolean()).isTrue();
        assertThat(mainFailed.path("player").path("coins").asInt()).isEqualTo(initialCoins);
        assertThat(mainFailed.path("adventure").path("clears").path("county-exam-paper").asInt()).isZero();
        finish(cookie, mainFailed);

        JsonNode mainPassed = complete(begin(cookie, "county-exam-paper"), cookie, 0);
        assertThat(mainPassed.path("adventure").path("run").path("score").asInt()).isEqualTo(100);
        assertThat(mainPassed.path("adventure").path("run").path("costCommitted").asBoolean()).isTrue();
        assertThat(mainPassed.path("adventure").path("clears").path("county-exam-paper").asInt()).isOne();
        assertThat(mainPassed.path("adventure").path("inventory").path("county-pass-note").asInt()).isOne();
        finish(cookie, mainPassed);
        rejectBegin(cookie, "county-exam-paper");

        state = worldStates.find(learner, WorldRegistry.ANCIENT_OFFICIAL);
        state.with("adventure").put("locationId", "old-school");
        int sideCoins = state.path("player").path("coins").asInt();
        int sideFavorability = state.path("npcs").path(0).path("favorability").asInt();
        worldStates.save(learner, WorldRegistry.ANCIENT_OFFICIAL, state);

        JsonNode sideFailed = complete(begin(cookie, "story-letter"), cookie, 3);
        assertThat(sideFailed.path("adventure").path("run").path("score").asInt()).isEqualTo(40);
        assertThat(sideFailed.path("adventure").path("run").path("rewards").isEmpty()).isTrue();
        assertThat(sideFailed.path("player").path("coins").asInt()).isEqualTo(sideCoins);
        assertThat(sideFailed.path("npcs").path(0).path("favorability").asInt()).isEqualTo(sideFavorability);
        assertThat(sideFailed.path("adventure").path("clears").path("story-letter").asInt()).isZero();
        finish(cookie, sideFailed);

        JsonNode sidePassed = complete(begin(cookie, "story-letter"), cookie, 2);
        assertThat(sidePassed.path("adventure").path("run").path("score").asInt()).isEqualTo(60);
        assertThat(sidePassed.path("adventure").path("clears").path("story-letter").asInt()).isOne();
        assertThat(sidePassed.path("player").path("coins").asInt()).isEqualTo(sideCoins + 12);
        assertThat(sidePassed.path("npcs").path(0).path("favorability").asInt()).isEqualTo(sideFavorability + 2);
        finish(cookie, sidePassed);
        rejectBegin(cookie, "story-letter");
    }

    private JsonNode complete(JsonNode game, Cookie cookie, int formalMisses) throws Exception {
        int missed = 0;
        while ("active".equals(game.path("adventure").path("run").path("status").asText())) {
            boolean correct = missed >= formalMisses;
            String answeredQuestion = game.path("attempt").path("question").path("id").asText();
            if (!correct) missed++;
            game = answer(cookie, game, correct);
            JsonNode run = game.path("adventure").path("run");
            // 普通正式训练单层化：无论 correct / wrong / partial 都只推进一个正式题 slot，
            // 不再 training、不再 retry 父题、也不再创建诊断会话。
            assertThat(run.path("training").asBoolean()).isFalse();
            assertThat(run.path("retryQuestionId").isNull()).isTrue();
            assertThat(run.path("diagnosisSessionId").isNull()).isTrue();
            assertThat(run.path("seenQuestionIds")).anySatisfy(id -> assertThat(id.asText()).isEqualTo(answeredQuestion));
            if ("active".equals(run.path("status").asText())) game = next(cookie, game);
        }
        return game;
    }

    private void rejectBegin(Cookie cookie, String activityId) throws Exception {
        mvc.perform(post("/api/v1/worlds/ancient-official/activities").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":\"%s\"}".formatted(activityId)))
                .andExpect(status().isConflict());
    }
}

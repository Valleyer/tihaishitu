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
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:hanmen-reachability;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class HanmenReachabilityIntegrationTest extends DiagnosticWorldTestSupport {
    @Autowired WorldStateStore worldStates;

    @Test
    void newLifeReachesPrefectureExamWithoutLegacyAttributes() throws Exception {
        ExamScenario scenario = examScenario();
        // 整条路线共需 105 道 RANDOM 正式题；补足同日硬去重候选。
        for (String point : scenario.points())
            for (int extra = 0; extra < 12; extra++)
                question(2, java.util.List.of(relation(point, "core")));

        Cookie cookie = register("hanmen-reachability");
        String learner = jdbc.queryForObject("SELECT id FROM learner_account WHERE username=?", String.class,
                "hanmen-reachability");
        jdbc.update("UPDATE learner_study_profile SET focus_mode='manual' WHERE learner_id=?", learner);
        for (int index = 0; index < scenario.points().size(); index++)
            jdbc.update("INSERT INTO learner_focus_knowledge(learner_id,knowledge_point_id,sort_order) VALUES (?,?,?)",
                    learner, scenario.points().get(index), index);
        initialize(cookie);

        JsonNode state = worldStates.find(learner, WorldRegistry.ANCIENT_OFFICIAL);
        assertThat(state.path("adventure").path("attributes").isEmpty()).isTrue();

        for (int run = 0; run < 10; run++) completeAndFinish(cookie, "read");
        for (int run = 0; run < 3; run++) completeAndFinish(cookie, "copy-work");

        completeAndFinish(cookie, "trial-ink");
        JsonNode qingxiTask = completeAndFinish(cookie, "story-letter");
        assertThat(qingxiTask.path("player").path("knowledge").asInt()).isEqualTo(50);
        assertThat(qingxiTask.path("player").path("coins").asInt()).isEqualTo(36);
        assertThat(qingxiTask.path("player").path("reputation").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(qingxiTask.path("adventure").path("attributes").isEmpty()).isTrue();

        travel(cookie, "exam-street");
        registerExam(cookie, "county-exam");
        JsonNode county = completeAndFinish(cookie, "county-exam-paper");
        assertThat(county.path("adventure").path("exams").path("county-exam").path("status").asText())
                .isEqualTo("passed");

        travel(cookie, "prefecture-road");
        completeAndFinish(cookie, "prefecture-departure");
        travel(cookie, "prefecture-inn");
        JsonNode linchuan = completeAndFinish(cookie, "story-linchuan-arrival");
        assertThat(linchuan.path("flags").toString()).contains("linchuan-arrived");

        travel(cookie, "prefecture-exam-street");
        registerExam(cookie, "prefecture-exam");
        JsonNode prefecture = completeAndFinish(cookie, "prefecture-exam-paper");
        assertThat(prefecture.path("adventure").path("exams").path("prefecture-exam").path("status").asText())
                .isEqualTo("passed");
        assertThat(prefecture.path("adventure").path("attributes").isEmpty()).isTrue();
    }

    private JsonNode completeAndFinish(Cookie cookie, String activityId) throws Exception {
        JsonNode game = begin(cookie, activityId);
        while ("active".equals(game.path("adventure").path("run").path("status").asText())) {
            game = answer(cookie, game, true);
            if ("active".equals(game.path("adventure").path("run").path("status").asText()))
                game = next(cookie, game);
        }
        return finish(cookie, game);
    }

    private void travel(Cookie cookie, String locationId) throws Exception {
        mvc.perform(post("/api/v1/worlds/ancient-official/travel").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"%s\"}".formatted(locationId)))
                .andExpect(status().isOk());
    }

    private void registerExam(Cookie cookie, String examId) throws Exception {
        mvc.perform(post("/api/v1/worlds/ancient-official/exams/register").with(csrf()).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"examId\":\"%s\"}".formatted(examId)))
                .andExpect(status().isOk());
    }
}

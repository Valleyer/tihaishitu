package cn.tihaishitu;

import cn.tihaishitu.learner.LearnerAuthService;
import cn.tihaishitu.game.QuestionAttemptStore;
import cn.tihaishitu.world.WorldActionContext;
import cn.tihaishitu.world.WorldRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:world-state;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class WorldStateIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc;
    @Autowired QuestionAttemptStore attempts; @Autowired ObjectMapper mapper;

    @Test void oneWorldStatePerLearnerAndInitializationIsIdempotencyProtected() throws Exception {
        Cookie first = register("world_a"), second = register("world_b");
        initialize(first, "甲生"); initialize(second, "乙生");
        mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"characterName\":\"重来\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/worlds/ancient-official").cookie(first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.player.name").value("甲生"));
        mvc.perform(get("/api/v1/worlds/ancient-official").cookie(second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.player.name").value("乙生"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM learner_world_state", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM game_save", Integer.class)).isZero();

        String learnerId = jdbc.queryForObject("SELECT id FROM learner_account WHERE username = 'world_a'", String.class);
        String attemptId = java.util.UUID.randomUUID().toString();
        WorldActionContext.run(learnerId, WorldRegistry.ANCIENT_OFFICIAL, () -> {
            var question = mapper.createObjectNode().put("id", "question-uuid").put("gradingMode", "auto");
            attempts.create(attemptId, WorldRegistry.ANCIENT_OFFICIAL, "question-uuid", question,
                    com.fasterxml.jackson.databind.node.BooleanNode.TRUE);
            attempts.recordAnswer(attempts.find(attemptId, WorldRegistry.ANCIENT_OFFICIAL),
                    com.fasterxml.jackson.databind.node.BooleanNode.TRUE, true);
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT game_id FROM study_attempt WHERE id = ?", String.class, attemptId)).isNull();
        assertThat(jdbc.queryForObject("SELECT learner_id FROM study_attempt WHERE id = ?", String.class, attemptId)).isEqualTo(learnerId);
        assertThat(jdbc.queryForObject("SELECT world_id FROM answer_record WHERE attempt_id = ?", String.class, attemptId))
                .isEqualTo(WorldRegistry.ANCIENT_OFFICIAL);
    }
    private void initialize(Cookie cookie,String name) throws Exception { mvc.perform(post("/api/v1/worlds/ancient-official/initialize").with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
            .content("{\"characterName\":\"%s\",\"gender\":\"男\",\"origin\":\"寒门读书人\"}".formatted(name)))
            .andExpect(status().isCreated()); }
    private Cookie register(String username) throws Exception { return mvc.perform(post("/api/v1/learner/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"%s\",\"displayName\":\"%s\",\"password\":\"password-123\"}".formatted(username,username)))
            .andReturn().getResponse().getCookie(LearnerAuthService.COOKIE); }
}

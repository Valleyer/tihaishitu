package cn.tihaishitu;

import cn.tihaishitu.game.GameContent;
import cn.tihaishitu.game.GameFactory;
import cn.tihaishitu.game.NewGameRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ContentExpansionIntegrationTest {
    @Autowired
    GameContent content;

    @Autowired
    GameFactory factory;

    @Test
    void prefectureContentIsLoadedAndUsesGlobalQuestionRules() {
        assertThat(content.location("prefecture-archive")).isPresent();
        assertThat(content.companion("pei")).isPresent();
        assertThat(content.companion("su")).isPresent();
        assertThat(content.item("prefecture-pass-note")).isPresent();
        assertThat(content.exam("prefecture-exam")).isPresent();

        ObjectNode ordinary = content.activity("story-wharf-ledger").orElseThrow();
        assertThat(ordinary.path("rounds").asInt()).isEqualTo(5);
        assertThat(ordinary.path("passScore").asInt()).isEqualTo(60);
        assertThat(ordinary.path("activityMode").asText()).isEqualTo("task");
        assertThat(ordinary.path("tiers").size()).isEqualTo(2);

        ObjectNode main = content.activity("prefecture-exam-paper").orElseThrow();
        assertThat(main.path("rounds").asInt()).isEqualTo(10);
        assertThat(main.path("passScore").asInt()).isEqualTo(100);
        assertThat(main.path("tiers").size()).isEqualTo(2);
    }

    @Test
    void newAndOldSavesReceiveEveryConfiguredExamAndCharacter() {
        ObjectNode game = factory.create(new NewGameRequest(
                "折叶", "男", "", List.of(), Map.of(), "normal", "gentle"
        ));
        assertThat(game.path("adventure").path("exams").path("county-exam").isObject()).isTrue();
        assertThat(game.path("adventure").path("exams").path("prefecture-exam").isObject()).isTrue();
        assertThat(game.path("npcs").findValuesAsText("id")).contains("pei", "su");

        ((ObjectNode) game.path("adventure").path("exams")).remove("prefecture-exam");
        game.withArray("npcs").removeAll();
        factory.hydrate(game);

        assertThat(game.path("adventure").path("exams").path("prefecture-exam").path("status").asText())
                .isEqualTo("unregistered");
        assertThat(game.path("npcs").findValuesAsText("id")).contains("pei", "su");
    }
}

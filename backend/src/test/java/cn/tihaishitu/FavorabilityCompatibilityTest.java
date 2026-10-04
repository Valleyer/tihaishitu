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
class FavorabilityCompatibilityTest {
    @Autowired GameFactory factory;
    @Autowired GameContent content;

    @Test
    void oldNpcStateHydratesToMaximumAndRemovesParallelRelationshipFields() {
        ObjectNode game = factory.create(new NewGameRequest("折叶", "男", "", List.of(), Map.of(), "normal", "gentle"));
        ObjectNode npc = (ObjectNode) game.path("npcs").path(0);
        npc.remove("favorability");
        npc.put("affinity", 12);
        npc.put("trust", 25);

        factory.hydrate(game);

        assertThat(npc.path("favorability").asInt()).isEqualTo(25);
        assertThat(npc.has("affinity")).isFalse();
        assertThat(npc.has("trust")).isFalse();
    }

    @Test
    void companionGatesAndRewardsExposeOnlyCanonicalFavorability() {
        ObjectNode companion = content.companion("lu").orElseThrow();
        assertThat(companion.path("topics").path(0).has("minFavorability")).isTrue();
        assertThat(companion.path("topics").path(0).has("minAffinity")).isFalse();
        assertThat(companion.path("milestones").path(0).has("favorability")).isTrue();
        assertThat(companion.path("milestones").path(0).has("affinity")).isFalse();

        ObjectNode reward = (ObjectNode) content.activity("read-lu").orElseThrow()
                .path("tiers").path(1).path("rewards");
        assertThat(reward.path("favorability").path("lu").asInt()).isEqualTo(2);
    }
}

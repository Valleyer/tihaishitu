package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RewardNormalizationTest {
    @Autowired GameContent content;
    @Autowired ObjectMapper mapper;

    @Test
    void legacyTaskTiersCollapseByMaximumAndUnionIntoOneCompletionReward() throws Exception {
        ObjectNode legacy = (ObjectNode) mapper.readTree("""
                {
                  "id":"legacy-side","kind":"story","repeatable":false,
                  "rounds":3,"passScore":60,"requirements":{},
                  "tiers":[
                    {"minScore":0,"label":"未竟","rewards":{},"dialogue":"再来"},
                    {"minScore":60,"label":"合格","rewards":{"coins":10,"affinity":{"lu":3},"flags":["a"]},"firstRewards":{"items":{"token":1},"title":"初阶"},"dialogue":"合格"},
                    {"minScore":80,"label":"出众","rewards":{"coins":15,"trust":{"lu":5},"attributes":{"insight":2},"flags":["b"]},"dialogue":"出众"},
                    {"minScore":100,"label":"无瑕","rewards":{"coins":20,"favorability":{"gu":4}},"firstRewards":{"items":{"token":2},"title":"高阶"},"dialogue":"无瑕"}
                  ]
                }
                """);

        ObjectNode task = content.normalizeActivityForCompatibility(legacy);
        ObjectNode reward = (ObjectNode) task.path("completionReward");

        assertThat(task.path("activityMode").asText()).isEqualTo("task");
        assertThat(task.path("rounds").asInt()).isEqualTo(5);
        assertThat(task.path("tiers").size()).isEqualTo(2);
        assertThat(reward.path("coins").asInt()).isEqualTo(20);
        assertThat(reward.path("attributes").path("insight").asInt()).isEqualTo(2);
        assertThat(reward.path("items").path("token").asInt()).isEqualTo(2);
        assertThat(reward.path("favorability").path("lu").asInt()).isEqualTo(5);
        assertThat(reward.path("favorability").path("gu").asInt()).isEqualTo(4);
        assertThat(reward.path("flags")).extracting(JsonNode::asText).containsExactly("a", "b");
        assertThat(reward.path("title").asText()).isEqualTo("高阶");
        assertThat(reward.has("affinity")).isFalse();
        assertThat(reward.has("trust")).isFalse();
    }

    @Test
    void legacyAffinityAndTrustWithinOneRewardUseMaximumInsteadOfSum() throws Exception {
        ObjectNode reward = content.normalizeRewardForCompatibility(mapper.readTree(
                "{\"affinity\":{\"lu\":3},\"trust\":{\"lu\":5}}"));
        assertThat(reward.path("favorability").path("lu").asInt()).isEqualTo(5);
        assertThat(reward.has("affinity")).isFalse();
        assertThat(reward.has("trust")).isFalse();
    }
}

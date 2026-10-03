package cn.tihaishitu.world;

import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.game.GameFactory;
import cn.tihaishitu.game.NewGameRequest;
import cn.tihaishitu.learner.LearnerContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AncientOfficialWorldService {
    private final WorldStateStore states;
    private final GameFactory factory;
    public AncientOfficialWorldService(WorldStateStore states, GameFactory factory) { this.states = states; this.factory = factory; }

    public ObjectNode get() { return factory.hydrate(states.find(LearnerContext.learnerId(), WorldRegistry.ANCIENT_OFFICIAL)); }

    @Transactional
    public ObjectNode initialize(InitializeRequest request) {
        String learnerId = LearnerContext.learnerId();
        if (states.exists(learnerId, WorldRegistry.ANCIENT_OFFICIAL))
            throw new ApiException(HttpStatus.CONFLICT, "这个世界已经初始化，不能重复创建。");
        if (request.characterName() == null || request.characterName().isBlank() || request.characterName().trim().length() > 12)
            throw new ApiException(HttpStatus.BAD_REQUEST, "角色姓名须为 1–12 个字。");
        ObjectNode state = factory.create(new NewGameRequest(request.characterName().trim(), request.gender(), request.origin(),
                List.of(), Map.of(), "normal", "standard"));
        state.remove("config");
        state.put("id", WorldRegistry.ANCIENT_OFFICIAL);
        state.put("worldId", WorldRegistry.ANCIENT_OFFICIAL);
        state.put("revision", 0);
        states.insert(learnerId, WorldRegistry.ANCIENT_OFFICIAL, state);
        return state;
    }

    public record InitializeRequest(String characterName, String gender, String origin) {}
}

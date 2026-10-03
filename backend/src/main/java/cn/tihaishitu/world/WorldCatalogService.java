package cn.tihaishitu.world;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class WorldCatalogService {
    private final WorldRegistry registry;
    private final WorldStateStore states;
    public WorldCatalogService(WorldRegistry registry, WorldStateStore states) { this.registry = registry; this.states = states; }
    public List<WorldRegistry.WorldDefinition> forLearner(String learnerId) {
        return registry.all().stream().map(world -> {
            boolean initialized = world.enabled() && states.exists(learnerId, world.id());
            return new WorldRegistry.WorldDefinition(world.id(), world.name(), world.description(), world.enabled(),
                    initialized, initialized ? states.updatedAt(learnerId, world.id()) : null, world.entryPath());
        }).toList();
    }
}

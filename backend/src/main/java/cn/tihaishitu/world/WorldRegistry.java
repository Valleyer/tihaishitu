package cn.tihaishitu.world;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class WorldRegistry {
    public static final String ANCIENT_OFFICIAL = "ancient-official";
    public List<WorldDefinition> all() {
        return List.of(
                new WorldDefinition(ANCIENT_OFFICIAL, "题海仕途", "从青溪县启程，以学问叩开仕途。", true, false, null, "/worlds/ancient-official"),
                new WorldDefinition("cultivation", "问道题山", "修真学习世界，筹备中。", false, false, null, null),
                new WorldDefinition("cyber-scholar", "星港学城", "未来学习世界，筹备中。", false, false, null, null));
    }
    public record WorldDefinition(String id, String name, String description, boolean enabled,
                                  boolean initialized, String updatedAt, String entryPath) {}
}

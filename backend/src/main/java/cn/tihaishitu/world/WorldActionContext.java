package cn.tihaishitu.world;

import java.util.function.Supplier;

public final class WorldActionContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private WorldActionContext() {}

    public static <T> T run(String learnerId, String worldId, Supplier<T> action) {
        if (CURRENT.get() != null) throw new IllegalStateException("世界操作上下文不可嵌套。");
        CURRENT.set(new Scope(learnerId, worldId));
        try { return action.get(); }
        finally { CURRENT.remove(); }
    }

    public static Scope currentOrNull() { return CURRENT.get(); }
    public static boolean active() { return CURRENT.get() != null; }
    public record Scope(String learnerId, String worldId) {}
}

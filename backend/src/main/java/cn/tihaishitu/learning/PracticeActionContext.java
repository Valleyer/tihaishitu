package cn.tihaishitu.learning;

public final class PracticeActionContext {
    public record Scope(String learnerId, String practiceSessionId) {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private PracticeActionContext() {}

    public static Scope currentOrNull() { return CURRENT.get(); }

    public static <T> T within(String learnerId, String practiceSessionId,
                               java.util.function.Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(learnerId, practiceSessionId));
        try { return action.get(); }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}

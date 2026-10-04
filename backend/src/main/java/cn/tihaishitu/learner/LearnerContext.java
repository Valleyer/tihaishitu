package cn.tihaishitu.learner;

import cn.tihaishitu.common.ApiException;
import org.springframework.http.HttpStatus;

public final class LearnerContext {
    private static final ThreadLocal<LearnerPrincipal> CURRENT = new ThreadLocal<>();

    private LearnerContext() {}

    static void set(LearnerPrincipal learner) { CURRENT.set(learner); }
    static void clear() { CURRENT.remove(); }
    public static LearnerPrincipal current() {
        LearnerPrincipal learner = CURRENT.get();
        if (learner == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录学习账号。");
        return learner;
    }
    public static String learnerId() { return current().id(); }

    public record LearnerPrincipal(String id, String username, String displayName, long revision) {}
}

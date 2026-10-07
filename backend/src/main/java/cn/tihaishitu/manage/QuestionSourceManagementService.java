package cn.tihaishitu.manage;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
public class QuestionSourceManagementService {
    private static final Set<String> TYPES = Set.of("real_exam", "mock", "custom");
    private static final Set<String> STATUSES = Set.of("active", "disabled");
    private final QuestionSourceManagementStore store;
    private final KnowledgeManagementStore users;

    public QuestionSourceManagementService(QuestionSourceManagementStore store, KnowledgeManagementStore users) {
        this.store = store; this.users = users;
    }
    public QuestionSourceManagementStore.SourceView create(QuestionSourceManagementStore.SourceInput input, Authentication auth) {
        return store.create(valid(input), actor(auth));
    }
    public QuestionSourceManagementStore.SourceView update(String id, QuestionSourceManagementStore.SourceInput input,
                                                             long revision, Authentication auth) {
        return store.update(id, valid(input), revision, actor(auth));
    }
    @Transactional
    public QuestionSourceManagementStore.SourceView resolveOrCreate(String type, String name, String actorId) {
        String canonical = required(name, "来源名称不能为空。");
        if (!TYPES.contains(type)) bad("来源类型不合法。");
        var source = store.findByIdentity(type, canonical).orElseGet(() -> store.create(
                new QuestionSourceManagementStore.SourceInput(type, canonical, canonical, "active"), actorId));
        if (!"active".equals(source.status())) bad("已停用来源不能用于新的题目绑定。");
        return source;
    }
    public QuestionSourceManagementStore.SourceView requireForBinding(String id, String existingId) {
        if (id == null || id.isBlank()) bad("请选择来源。");
        var source = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选来源不存在。"));
        if (!"active".equals(source.status()) && !source.id().equals(existingId)) bad("已停用来源不能用于新的题目绑定。");
        return source;
    }
    private QuestionSourceManagementStore.SourceInput valid(QuestionSourceManagementStore.SourceInput input) {
        if (input == null || !TYPES.contains(input.sourceType()) || !STATUSES.contains(input.status())) bad("来源类型或状态不合法。");
        return new QuestionSourceManagementStore.SourceInput(input.sourceType(), required(input.canonicalName(), "正式名称不能为空。"),
                required(input.displayName(), "展示名称不能为空。"), input.status());
    }
    private String actor(Authentication auth) { return users.userId(auth.getName()); }
    private static String required(String value, String message) { if (value == null || value.isBlank()) bad(message); return value.trim(); }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

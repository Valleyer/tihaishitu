package cn.tihaishitu.learning;

/**
 * 题面上展示的知识点标签。
 *
 * <p>{@code role} 只表达主次：{@code core} 为主标签，{@code auxiliary} 为辅助标签。
 * 两种角色都展示，也都不再决定题目能不能做或是否进入 Mastery 分母。</p>
 */
public record KnowledgePointTag(String id, String name, String role) {
    public KnowledgePointTag {
        role = role == null || role.isBlank() ? "core" : role;
    }
}

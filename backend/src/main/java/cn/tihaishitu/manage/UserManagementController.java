package cn.tihaishitu.manage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/manage/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserManagementController {
    private static final Set<String> ALLOWED_ROLES = Set.of("CONTRIBUTOR", "REVIEWER", "ADMIN");
    private static final Set<String> ALLOWED_STATUSES = Set.of("active", "disabled");
    private final ManageUserStore users;
    private final PasswordEncoder encoder;
    private final KnowledgeManagementStore audit;

    public UserManagementController(ManageUserStore users, PasswordEncoder encoder, KnowledgeManagementStore audit) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
    }

    @GetMapping
    List<ManageUserView> all() { return users.findAll(); }

    @PostMapping
    ManageUserView create(@Valid @RequestBody CreateRequest body, Authentication authentication) {
        validateRoles(body.roles());
        if (users.findView(body.username()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已经存在。");
        }
        ManageUserView created = users.create(body.username().trim(), body.displayName().trim(),
                encoder.encode(body.password()), body.roles());
        audit.audit(audit.userId(authentication.getName()), "USER_CREATED", "app_user", created.id(),
                java.util.Map.of("roles", body.roles()));
        return created;
    }

    @PutMapping("/{id}")
    ManageUserView update(@PathVariable String id, @Valid @RequestBody UpdateRequest body,
                          Authentication authentication) {
        validateRoles(body.roles());
        if (!ALLOWED_STATUSES.contains(body.status())) bad("账号状态不合法。");
        ManageUserView before = users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在。"));
        boolean removesActiveAdmin = before.roles().contains("ADMIN")
                && (!body.roles().contains("ADMIN") || "disabled".equals(body.status()));
        if (removesActiveAdmin && users.adminCount() <= 1) bad("不能禁用或降级最后一个有效管理员。");
        String hash = body.password() == null || body.password().isBlank() ? null : encoder.encode(body.password());
        ManageUserView changed = users.update(id, body.displayName().trim(), body.status(), hash,
                body.roles(), body.expectedRevision());
        if (changed == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已被其他人修改，请重新加载。");
        audit.audit(audit.userId(authentication.getName()), "USER_ROLE_UPDATED", "app_user", id,
                java.util.Map.of("roles", body.roles(), "status", body.status()));
        return changed;
    }

    private static void validateRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty() || !ALLOWED_ROLES.containsAll(roles)) bad("至少选择一个合法角色。");
    }
    private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record CreateRequest(
            @NotBlank String username, @NotBlank String displayName,
            @NotBlank @Size(min = 10) String password, @NotNull Set<String> roles) {}
    public record UpdateRequest(
            @NotBlank String displayName, @NotBlank String status, String password,
            @NotNull Set<String> roles, @NotNull Long expectedRevision) {}
}

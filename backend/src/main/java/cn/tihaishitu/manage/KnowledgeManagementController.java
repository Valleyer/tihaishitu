package cn.tihaishitu.manage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/manage/knowledge-points")
public class KnowledgeManagementController {
    private static final Set<String> ROLES = Set.of("core", "auxiliary");
    private static final Set<String> STATUSES = Set.of("active", "deprecated");
    private final KnowledgeManagementStore store;

    public KnowledgeManagementController(KnowledgeManagementStore store) {
        this.store = store;
    }

    @GetMapping
    PageResult<KnowledgeManagementStore.KnowledgeView> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) String chapter,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) int size) {
        return store.search(query, subject, section, chapter, status, page, Math.min(size, 100));
    }

    @GetMapping("/{id}")
    KnowledgeManagementStore.KnowledgeView get(@PathVariable String id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "知识点不存在。"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    KnowledgeManagementStore.KnowledgeView update(
            @PathVariable String id, @Valid @RequestBody UpdateRequest body, Authentication authentication) {
        if (!ROLES.contains(body.defaultRole()) || !STATUSES.contains(body.status())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "知识点角色或状态不合法。");
        }
        return store.update(id, new KnowledgeManagementStore.KnowledgeUpdate(
                body.name().trim(), body.defaultRole(), body.status(), value(body.description()),
                value(body.explanation()), body.mergedIntoId(), body.aliases() == null ? List.of() : body.aliases(),
                body.expectedRevision()), store.userId(authentication.getName()));
    }

    private static String value(String input) { return input == null ? "" : input; }

    public record UpdateRequest(
            @NotBlank String name,
            @NotBlank String defaultRole,
            @NotBlank String status,
            String description,
            String explanation,
            String mergedIntoId,
            List<String> aliases,
            @NotNull Long expectedRevision) {}
}

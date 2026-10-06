package cn.tihaishitu.manage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final KnowledgeManagementService service;

    public KnowledgeManagementController(KnowledgeManagementStore store, KnowledgeManagementService service) {
        this.store = store;
        this.service = service;
    }

    @GetMapping
    PageResult<KnowledgeManagementStore.KnowledgeView> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String section,
            @RequestParam(required = false) String chapter,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String chapterId,
            @RequestParam(defaultValue = "all") String membership,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        if (!Set.of("all", "assigned", "unassigned").contains(membership)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "文集归属筛选不合法。");
        }
        return store.search(query, subject, section, chapter, status, bookId, chapterId,
                membership, page, Math.min(size, 100));
    }

    @GetMapping("/facets")
    KnowledgeManagementStore.KnowledgeFacets facets() { return store.facets(); }

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

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasRole('ADMIN')")
    KnowledgeManagementStore.KnowledgeMergeResult merge(
            @PathVariable String id, @Valid @RequestBody MergeRequest body, Authentication authentication) {
        return service.merge(id, body.targetId().trim(), body.expectedRevision(), body.reason(),
                store.userId(authentication.getName()));
    }

    @PostMapping("/bulk-delete")
    @PreAuthorize("hasRole('ADMIN')")
    KnowledgeManagementService.BulkDeleteResult bulkDelete(
            @RequestBody BulkDeleteRequest body, Authentication authentication) {
        if (body == null || body.ids() == null || body.ids().isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "请选择要删除的知识点。");
        }
        return service.bulkDelete(body.ids(), store.userId(authentication.getName()));
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

    public record MergeRequest(
            @NotBlank String targetId,
            @NotBlank String reason,
            @NotNull Long expectedRevision) {}

    public record BulkDeleteRequest(List<String> ids) {}
}

package cn.tihaishitu.manage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manage/sources")
public class QuestionSourceManagementController {
    private final QuestionSourceManagementStore store;
    private final QuestionSourceManagementService service;
    public QuestionSourceManagementController(QuestionSourceManagementStore store, QuestionSourceManagementService service) {
        this.store = store; this.service = service;
    }
    @GetMapping
    @PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
    PageResult<QuestionSourceManagementStore.SourceView> search(@RequestParam(required=false) String query,
            @RequestParam(required=false) String sourceType, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") @Min(0) int page, @RequestParam(defaultValue="20") @Min(1) int size) {
        return store.search(query, sourceType, status, page, Math.min(size, 20));
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
    QuestionSourceManagementStore.SourceView get(@PathVariable String id) {
        return store.find(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "来源不存在。"));
    }
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    QuestionSourceManagementStore.SourceView create(@Valid @RequestBody SourceRequest request, Authentication auth) {
        return service.create(input(request), auth);
    }
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    QuestionSourceManagementStore.SourceView update(@PathVariable String id,
            @Valid @RequestBody SourceRequest request, Authentication auth) {
        if (request.expectedRevision() == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "修改来源必须携带 expectedRevision。");
        return service.update(id, input(request), request.expectedRevision(), auth);
    }
    private static QuestionSourceManagementStore.SourceInput input(SourceRequest r) {
        return new QuestionSourceManagementStore.SourceInput(r.sourceType(), r.canonicalName(), r.displayName(), r.status());
    }
    public record SourceRequest(@NotBlank String sourceType, @NotBlank String canonicalName,
                                @NotBlank String displayName, @NotBlank String status, Long expectedRevision) {}
}

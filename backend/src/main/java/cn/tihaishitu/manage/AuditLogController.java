package cn.tihaishitu.manage;

import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manage/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {
    private final AuditLogStore store;

    public AuditLogController(AuditLogStore store) {
        this.store = store;
    }

    @GetMapping
    PageResult<AuditLogStore.AuditLogView> search(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) int size) {
        return store.search(action, entityType, actor, page, Math.min(size, 100));
    }
}

package cn.tihaishitu.manage;

import cn.tihaishitu.catalog.CatalogAdminGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GlobalQuestionBatchImportController {
    private final GlobalQuestionBatchImportService service;
    private final CatalogAdminGuard guard;
    private final KnowledgeManagementStore knowledgeStore;

    public GlobalQuestionBatchImportController(GlobalQuestionBatchImportService service,
                                               CatalogAdminGuard guard,
                                               KnowledgeManagementStore knowledgeStore) {
        this.service = service;
        this.guard = guard;
        this.knowledgeStore = knowledgeStore;
    }

    @PostMapping("/api/v1/admin/questions/import")
    @ResponseStatus(HttpStatus.CREATED)
    GlobalQuestionBatchImportService.ImportResult machineImport(
            @RequestHeader(name = "X-Admin-Key", required = false) String key,
            @RequestBody JsonNode request) {
        guard.require(key);
        return service.importBatch(request, null);
    }

    @PostMapping("/api/v1/manage/imports/questions")
    @PreAuthorize("hasRole('ADMIN')")
    GlobalQuestionBatchImportService.ImportResult browserImport(
            @RequestBody JsonNode request, Authentication authentication) {
        return service.importBatch(request, knowledgeStore.userId(authentication.getName()));
    }
}

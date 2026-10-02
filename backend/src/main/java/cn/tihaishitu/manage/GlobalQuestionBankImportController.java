package cn.tihaishitu.manage;

import cn.tihaishitu.catalog.CatalogAdminGuard;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GlobalQuestionBankImportController {
    private final GlobalQuestionBankImportService service;
    private final CatalogAdminGuard guard;
    private final KnowledgeManagementStore knowledgeStore;

    public GlobalQuestionBankImportController(GlobalQuestionBankImportService service,
                                              CatalogAdminGuard guard,
                                              KnowledgeManagementStore knowledgeStore) {
        this.service = service;
        this.guard = guard;
        this.knowledgeStore = knowledgeStore;
    }

    @PostMapping("/api/v1/admin/global-question-banks/import")
    @ResponseStatus(HttpStatus.CREATED)
    GlobalQuestionBankImportService.ImportResult machineImport(
            @RequestHeader(name = "X-Admin-Key", required = false) String key,
            @RequestBody GlobalQuestionBankImportService.ImportRequest request) {
        guard.require(key);
        return service.importBank(request, null);
    }

    @PostMapping("/api/v1/manage/imports/question-bank")
    @PreAuthorize("hasRole('ADMIN')")
    GlobalQuestionBankImportService.ImportResult browserImport(
            @RequestBody GlobalQuestionBankImportService.ImportRequest request,
            Authentication authentication) {
        return service.importBank(request, knowledgeStore.userId(authentication.getName()));
    }
}

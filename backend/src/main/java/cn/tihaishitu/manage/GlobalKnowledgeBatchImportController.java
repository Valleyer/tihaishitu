package cn.tihaishitu.manage;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GlobalKnowledgeBatchImportController {
    private final GlobalKnowledgeBatchImportService service;
    private final KnowledgeManagementStore knowledgeStore;

    public GlobalKnowledgeBatchImportController(GlobalKnowledgeBatchImportService service,
                                                KnowledgeManagementStore knowledgeStore) {
        this.service = service;
        this.knowledgeStore = knowledgeStore;
    }

    @PostMapping("/api/v1/manage/imports/knowledge")
    @PreAuthorize("hasRole('ADMIN')")
    GlobalKnowledgeBatchImportService.ImportResult browserImport(
            @RequestBody JsonNode request, Authentication authentication) {
        return service.importBatch(request, knowledgeStore.userId(authentication.getName()));
    }
}

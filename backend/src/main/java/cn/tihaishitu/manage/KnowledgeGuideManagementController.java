package cn.tihaishitu.manage;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/manage")
@PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
public class KnowledgeGuideManagementController {
    private final KnowledgeGuideManagementService service;private final KnowledgeManagementStore knowledge;
    public KnowledgeGuideManagementController(KnowledgeGuideManagementService service,KnowledgeManagementStore knowledge){this.service=service;this.knowledge=knowledge;}
    @PostMapping("/knowledge-points/export-guides") public Map<String,Object> export(@RequestBody KnowledgeGuideManagementService.ExportRequest request){return service.export(request);}
    @PostMapping("/imports/knowledge-guides")
    @PreAuthorize("hasRole('ADMIN')")
    public KnowledgeGuideManagementService.ImportResult importBatch(@RequestBody JsonNode request,Authentication auth){return service.importBatch(request,knowledge.userId(auth.getName()));}
}

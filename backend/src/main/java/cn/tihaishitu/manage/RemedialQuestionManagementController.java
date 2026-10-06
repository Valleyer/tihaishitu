package cn.tihaishitu.manage;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/manage")
@PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
public class RemedialQuestionManagementController {
    private final RemedialQuestionManagementService service; private final KnowledgeManagementStore knowledge;
    public RemedialQuestionManagementController(RemedialQuestionManagementService service,KnowledgeManagementStore knowledge){this.service=service;this.knowledge=knowledge;}
    @PostMapping("/questions/export-remedial-source") public Map<String,Object> export(@RequestBody RemedialQuestionManagementService.ExportRequest request){return service.export(request);}
    @PostMapping("/imports/remedial-questions")
    @PreAuthorize("hasRole('ADMIN')")
    public RemedialQuestionManagementService.ImportResult importBatch(@RequestBody JsonNode request,Authentication auth){return service.importBatch(request,knowledge.userId(auth.getName()));}
}

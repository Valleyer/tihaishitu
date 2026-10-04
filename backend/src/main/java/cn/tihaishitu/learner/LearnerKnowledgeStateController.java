package cn.tihaishitu.learner;

import cn.tihaishitu.learning.LearnerKnowledgeStateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/learner/knowledge-states")
public class LearnerKnowledgeStateController {
    private final LearnerKnowledgeStateService service;
    public LearnerKnowledgeStateController(LearnerKnowledgeStateService service) { this.service = service; }

    @GetMapping("/{knowledgePointId}")
    public LearnerKnowledgeStateService.StateView get(@PathVariable String knowledgePointId) {
        return service.current(knowledgePointId);
    }

    @GetMapping
    public List<LearnerKnowledgeStateService.StateView> forBook(@RequestParam String bookId) {
        return service.currentForBook(bookId);
    }
}

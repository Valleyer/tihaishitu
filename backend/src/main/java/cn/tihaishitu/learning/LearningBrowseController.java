package cn.tihaishitu.learning;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningBrowseController {
    private final LearningBrowseStore store;
    public LearningBrowseController(LearningBrowseStore store) { this.store = store; }

    @GetMapping("/books") public List<Map<String, Object>> books() { return store.books(); }
    @GetMapping("/books/{id}") public Map<String, Object> book(@PathVariable String id) { return store.book(id); }
    @GetMapping("/knowledge-points/{id}") public Map<String, Object> knowledge(@PathVariable String id) { return store.knowledge(id); }
    @GetMapping("/knowledge-points/{id}/questions") public List<Map<String, Object>> knowledgeQuestions(@PathVariable String id) { return store.questionsForKnowledge(id); }
    @GetMapping("/questions/{id}") public Map<String, Object> question(@PathVariable String id) { return store.question(id); }
}

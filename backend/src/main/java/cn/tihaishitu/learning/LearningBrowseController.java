package cn.tihaishitu.learning;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import cn.tihaishitu.manage.PageResult;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningBrowseController {
    private final LearningBrowseStore store;
    public LearningBrowseController(LearningBrowseStore store) { this.store = store; }

    @GetMapping("/books") public List<Map<String, Object>> books() { return store.books(); }
    @GetMapping("/books/{id}") public Map<String, Object> book(@PathVariable String id) { return store.book(id); }
    @GetMapping("/knowledge-points")
    public PageResult<Map<String, Object>> knowledgePoints(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String chapterId,
            @RequestParam(required = false) String subject,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "分页参数不合法。");
        }
        return store.knowledgePoints(query, bookId, chapterId, subject, page, size);
    }
    @GetMapping("/knowledge-points/{id}") public Map<String, Object> knowledge(@PathVariable String id) { return store.knowledge(id); }
    @GetMapping("/knowledge-points/{id}/questions") public List<Map<String, Object>> knowledgeQuestions(@PathVariable String id) { return store.questionsForKnowledge(id); }
    @GetMapping("/questions/{id}") public Map<String, Object> question(@PathVariable String id) { return store.question(id); }
}

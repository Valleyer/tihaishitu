package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.manage.PageResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningBrowseController {
    private final LearningBrowseStore store;
    public LearningBrowseController(LearningBrowseStore store) { this.store = store; }

    @GetMapping("/books") public List<Map<String, Object>> books() {
        return store.books(LearnerContext.learnerId());
    }
    @GetMapping("/books/{id}") public Map<String, Object> book(@PathVariable String id) {
        return store.book(id, LearnerContext.learnerId());
    }
    @GetMapping("/knowledge-points")
    public PageResult<Map<String, Object>> knowledgePoints(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String bookId,
            @RequestParam(required = false) String chapterId,
            @RequestParam(required = false) String subject,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "分页参数不合法。");
        }
        return store.knowledgePoints(LearnerContext.learnerId(), query, bookId, chapterId, subject, page, size);
    }
    @GetMapping("/knowledge-points/facets") public Map<String, Object> knowledgeFacets() {
        return Map.of("subjects", store.knowledgeSubjects(LearnerContext.learnerId()));
    }
    @GetMapping("/knowledge-points/{id}") public Map<String, Object> knowledge(@PathVariable String id) {
        return store.knowledge(id, LearnerContext.learnerId());
    }
    @GetMapping("/knowledge-points/{id}/questions") public List<Map<String, Object>> knowledgeQuestions(@PathVariable String id) {
        return store.questionsForKnowledge(id, LearnerContext.learnerId());
    }
    @GetMapping("/questions/{id}") public Map<String, Object> question(@PathVariable String id) { return store.question(id); }
}

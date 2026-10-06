package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.manage.PageResult;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningBrowseController {
    private final LearningBrowseStore store;
    private final LearnerPracticeService practice;
    public LearningBrowseController(LearningBrowseStore store, LearnerPracticeService practice) {
        this.store = store;
        this.practice = practice;
    }

    @GetMapping("/books") public List<Map<String, Object>> books() {
        return store.books(LearnerContext.learnerId());
    }
    @GetMapping("/books/{id}") public Map<String, Object> book(@PathVariable String id) {
        return withChapterAvailability(store.book(id, LearnerContext.learnerId()), id, LearnerContext.learnerId());
    }

    /**
     * Chapter 列表统一补充 availableKnowledgePointCount（学习者当前真正可练的知识点数）。
     * trainableKnowledgePointCount 是目录静态值，二者不一致时前端应以 availableKnowledgePointCount 为准。
     * 整本文集只调用一次 Service：profile / scope 只读一次，Chapter 归属与候选题各查一次。
     */
    private Map<String, Object> withChapterAvailability(Map<String, Object> book, String bookId, String learnerId) {
        Object chapters = book.get("chapters");
        if (!(chapters instanceof List<?> list) || list.isEmpty()) return book;
        List<Map<String, Object>> normalized = new ArrayList<>(list.size());
        List<String> chapterIds = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> chapter = new LinkedHashMap<>();
            raw.forEach((key, value) -> chapter.put(String.valueOf(key), value));
            Object chapterId = chapter.get("id");
            if (chapterId != null) chapterIds.add(String.valueOf(chapterId));
            normalized.add(chapter);
        }
        Map<String, Integer> counts = practice.availableChapterKnowledgePointCounts(learnerId, bookId, chapterIds);
        List<Map<String, Object>> enriched = new ArrayList<>(normalized.size());
        for (Map<String, Object> chapter : normalized) {
            Object chapterId = chapter.get("id");
            chapter.put("availableKnowledgePointCount",
                    chapterId == null ? 0 : counts.getOrDefault(String.valueOf(chapterId), 0));
            enriched.add(chapter);
        }
        Map<String, Object> result = new LinkedHashMap<>(book);
        result.put("chapters", enriched);
        return result;
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
    @GetMapping("/knowledge-points/{id}/guide") public Map<String,Object> guide(@PathVariable String id) {
        return store.guide(id,LearnerContext.learnerId());
    }
    @GetMapping("/knowledge-points/{id}/neighbors") public Map<String,Object> neighbors(
            @PathVariable String id,@RequestParam String bookId,@RequestParam String chapterId) {
        return store.neighbors(id,bookId,chapterId,LearnerContext.learnerId());
    }
    @GetMapping("/questions/{id}") public Map<String, Object> question(@PathVariable String id) {
        return store.question(id, LearnerContext.learnerId());
    }
}

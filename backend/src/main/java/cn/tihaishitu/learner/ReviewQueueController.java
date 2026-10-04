package cn.tihaishitu.learner;

import cn.tihaishitu.learning.ReviewQueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learner/review-queue")
public class ReviewQueueController {
    private final ReviewQueueService service;

    public ReviewQueueController(ReviewQueueService service) { this.service = service; }

    @GetMapping
    public ReviewQueueService.ReviewQueue get() { return service.current(); }
}

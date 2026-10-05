package cn.tihaishitu.learner;

import cn.tihaishitu.learning.LearnerProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learner/progress")
public class LearnerProgressController {
    private final LearnerProgressService progress;

    public LearnerProgressController(LearnerProgressService progress) { this.progress = progress; }

    @GetMapping
    public LearnerProgressService.ProgressView get() { return progress.current(); }
}

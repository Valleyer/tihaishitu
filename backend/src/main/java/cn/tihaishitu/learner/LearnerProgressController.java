package cn.tihaishitu.learner;

import cn.tihaishitu.learning.LearnerActivityStatsService;
import cn.tihaishitu.learning.LearnerProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/learner/progress")
public class LearnerProgressController {
    private final LearnerProgressService progress;
    private final LearnerActivityStatsService activity;

    public LearnerProgressController(LearnerProgressService progress, LearnerActivityStatsService activity) {
        this.progress = progress;
        this.activity = activity;
    }

    @GetMapping
    public ProgressOverview get() {
        LearnerProgressService.ProgressView view = progress.current();
        return new ProgressOverview(view.generatedAt(), view.summary(), view.bands(), view.books(),
                view.recent(), activity.current());
    }

    /**
     * `/learner/progress` 的统一响应。
     *
     * <p>保留原有 {@code summary / bands / books / recent} 字段的语义兼容，新增
     * {@code activity}：进度与统计共用同一份有效答题口径，前端不再调用第二套统计接口。
     * {@code recent} 只保留最近接触的知识点列表；近 7 日与今日事实见 {@code activity}。</p>
     */
    public record ProgressOverview(Instant generatedAt, LearnerProgressService.Summary summary,
                                   Map<String, Integer> bands, List<LearnerProgressService.BookProgress> books,
                                   LearnerProgressService.RecentProgress recent,
                                   LearnerActivityStatsService.ActivityView activity) {}
}

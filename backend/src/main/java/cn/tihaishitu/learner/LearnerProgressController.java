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
                view.recent(), view.recentContacts(), activity.current());
    }

    /**
     * `/learner/progress` 的统一响应。
     *
     * <p>字段兼容（PR7 合并前复核修复）：</p>
     * <ul>
     *   <li>{@code summary / bands / books} 语义不变；</li>
     *   <li>{@code recent} 保留旧契约的全部字段（{@code gradedAttempts7d}、
     *       {@code distinctKnowledgePoints7d}、{@code activeStudyDays7d}、{@code daily}、
     *       {@code knowledgePoints}），保持 graded-only 语义，近 7 个上海业务日；</li>
     *   <li>{@code recentContacts} 是新增的「最近接触」列表，来源是有效 Attempt（含仅查看
     *       答案），按 {@code lastEffectiveContactAt} 倒序，与 {@code lastEvidenceAt} 区分；</li>
     *   <li>{@code activity} 是完整的统一有效答题口径，含 reveal-only。</li>
     * </ul>
     */
    public record ProgressOverview(Instant generatedAt, LearnerProgressService.Summary summary,
                                   Map<String, Integer> bands, List<LearnerProgressService.BookProgress> books,
                                   LearnerActivityStatsService.RecentProgress recent,
                                   List<LearnerProgressService.RecentContact> recentContacts,
                                   LearnerActivityStatsService.ActivityView activity) {}
}

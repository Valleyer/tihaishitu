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

    public LearnerProgressController(LearnerProgressService progress) { this.progress = progress; }

    @GetMapping
    public ProgressOverview get() {
        // 只调用一次：activity 已在 progressAt 内由同一次有效 Attempt 全历史读取派生，
        // 这里不得再调用 activity.current() 重复扫描。
        LearnerProgressService.ProgressView view = progress.current();
        return new ProgressOverview(view.generatedAt(), view.summary(), view.bands(), view.books(),
                view.recent(), view.recentContacts(), view.activity());
    }

    /**
     * `/learner/progress` 的统一响应。
     *
     * <p>字段兼容（PR7 合并前复核修复）：</p>
     * <ul>
     *   <li>{@code summary / bands / books} 语义不变；</li>
     *   <li>{@code recent} 保留旧契约的全部字段（{@code gradedAttempts7d}、
     *       {@code distinctKnowledgePoints7d}、{@code activeStudyDays7d}、{@code daily}、
     *       {@code knowledgePoints}）；数值是 graded-only 且按 {@code answered_at} 评分日归属
     *       近 7 个上海业务日，{@code knowledgePoints} 恢复为 Mastery Evidence 投影列表；</li>
     *   <li>{@code recentContacts} 是「最近接触」列表，来源是有效 Attempt（含仅查看答案），
     *       按 {@code lastEffectiveContactAt} 倒序，行为标签由最近 Attempt 的真实状态决定；</li>
     *   <li>{@code activity} 是完整的统一有效答题口径，含 reveal-only。</li>
     * </ul>
     */
    public record ProgressOverview(Instant generatedAt, LearnerProgressService.Summary summary,
                                   Map<String, Integer> bands, List<LearnerProgressService.BookProgress> books,
                                   LearnerProgressService.GradedRecentView recent,
                                   List<LearnerProgressService.RecentContact> recentContacts,
                                   LearnerActivityStatsService.ActivityView activity) {}
}

package cn.tihaishitu.learner;

import cn.tihaishitu.learning.LearnerActivityStatsService;
import cn.tihaishitu.learning.LearnerProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/v1/learner/progress")
public class LearnerProgressController {
    /** 活动趋势曲线允许的窗口天数；与旧统计页的 7/30/90 分段选择器一致。 */
    private static final Set<Integer> SUPPORTED_TREND_DAYS = Set.of(7, 30, 90);

    private final LearnerProgressService progress;

    public LearnerProgressController(LearnerProgressService progress) { this.progress = progress; }

    /**
     * 统一进度总览。
     *
     * @param days 可选，活动趋势曲线窗口，只接受 7 / 30 / 90，缺省 7；非法值返回 400，不静默改变窗口。
     */
    @GetMapping
    public ProgressOverview get(@RequestParam(required = false) Integer days) {
        int trendDays = days == null ? LearnerActivityStatsService.WINDOW_DAYS : days;
        if (!SUPPORTED_TREND_DAYS.contains(trendDays)) {
            throw new ResponseStatusException(BAD_REQUEST, "活动趋势窗口只支持 7、30 或 90 天。");
        }
        // 只调用一次：activity 已在 progressAt 内由同一次有效 Attempt 全历史读取派生，
        // 这里不得再调用统计服务重复扫描。
        LearnerProgressService.ProgressView view = progress.current(trendDays);
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
     *       {@code knowledgePoints}）；数值固定 graded-only 且按 {@code answered_at} 评分日归属
     *       近 7 个上海业务日，<b>不随 {@code days} 参数变化</b>；</li>
     *   <li>{@code recentContacts} 是「最近接触」列表，来源是有效 Attempt（含仅查看答案），
     *       按 {@code lastEffectiveContactAt} 倒序，行为标签由最近 Attempt 的真实状态决定；</li>
     *   <li>{@code activity} 是完整的统一有效答题口径。{@code metrics} 与 {@code outcomes} 固定
     *       口径（{@code activeStudyDays7d} 永远反映近 7 天），只有 {@code daily} 与
     *       {@code windowDays} 随 {@code days} 展开。</li>
     * </ul>
     */
    public record ProgressOverview(Instant generatedAt, LearnerProgressService.Summary summary,
                                   Map<String, Integer> bands, List<LearnerProgressService.BookProgress> books,
                                   LearnerProgressService.GradedRecentView recent,
                                   List<LearnerProgressService.RecentContact> recentContacts,
                                   LearnerActivityStatsService.ActivityView activity) {}
}

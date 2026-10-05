package cn.tihaishitu.learner;

import cn.tihaishitu.learning.LearnerStatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learner/statistics")
public class LearnerStatisticsController {
    private final LearnerStatisticsService statistics;

    public LearnerStatisticsController(LearnerStatisticsService statistics) { this.statistics = statistics; }

    @GetMapping
    public LearnerStatisticsService.StatisticsView get(@RequestParam(defaultValue = "30") int days) {
        return statistics.current(days);
    }
}

package cn.tihaishitu.manage;

import cn.tihaishitu.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/manage/question-reports")
@PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
public class QuestionReportManagementController {
    private static final Set<String> STATUSES = Set.of("open", "resolved", "dismissed");
    private static final Set<String> REASONS = Set.of(
            "content_error", "answer_error", "analysis_error", "format_error", "other");
    private final QuestionReportManagementStore store;
    public QuestionReportManagementController(QuestionReportManagementStore store) { this.store = store; }

    @GetMapping
    PageResult<QuestionReportManagementStore.ReportView> search(
            @RequestParam(required=false) String status, @RequestParam(required=false) String reason,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) throw bad("反馈状态不合法。");
        if (reason != null && !reason.isBlank() && !REASONS.contains(reason)) throw bad("问题类型不合法。");
        if (page < 0 || size != 20) throw bad("题目反馈固定每页 20 条。");
        return store.search(status, reason, page, size);
    }

    @PatchMapping("/{id}/status")
    QuestionReportManagementStore.ReportView update(@PathVariable String id, @Valid @RequestBody StatusRequest body) {
        if (!Set.of("resolved", "dismissed").contains(body.status())) throw bad("只能标记已处理或已忽略。");
        if (!store.updateStatus(id, body.status())) throw new ApiException(HttpStatus.NOT_FOUND, "题目反馈不存在。");
        return store.find(id);
    }

    private static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    public record StatusRequest(@NotBlank String status) {}
}

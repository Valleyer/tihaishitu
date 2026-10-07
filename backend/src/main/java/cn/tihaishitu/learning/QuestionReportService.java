package cn.tihaishitu.learning;

import cn.tihaishitu.common.ApiException;
import cn.tihaishitu.learner.LearnerContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class QuestionReportService {
    private static final Set<String> REASONS = Set.of(
            "content_error", "answer_error", "analysis_error", "format_error", "other");
    private final QuestionReportStore store;
    public QuestionReportService(QuestionReportStore store) { this.store = store; }

    @Transactional
    public ReportCreated create(String attemptId, String reason, String rawComment) {
        if (!REASONS.contains(reason)) throw new ApiException(HttpStatus.BAD_REQUEST, "问题类型不合法。");
        String comment = rawComment == null ? null : rawComment.trim();
        if (comment != null && comment.length() > 1000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "补充说明最多 1000 字。");
        if (comment != null && comment.isBlank()) comment = null;
        QuestionReportStore.AttemptFact attempt = store.formalAttempt(attemptId);
        if (attempt == null || !LearnerContext.learnerId().equals(attempt.learnerId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "正式作答记录不存在。");
        try {
            return new ReportCreated(store.create(attempt.learnerId(), attempt.questionId(), attemptId,
                    reason, comment), "open");
        } catch (DuplicateKeyException duplicate) {
            throw new ApiException(HttpStatus.CONFLICT, "这次作答已经提交过题目反馈。");
        }
    }

    public record ReportCreated(String id, String status) {}
}

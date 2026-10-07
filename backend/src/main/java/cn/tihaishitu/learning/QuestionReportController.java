package cn.tihaishitu.learning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/learner/question-reports")
public class QuestionReportController {
    private final QuestionReportService reports;
    public QuestionReportController(QuestionReportService reports) { this.reports = reports; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    QuestionReportService.ReportCreated create(@Valid @RequestBody CreateRequest body) {
        return reports.create(body.attemptId(), body.reason(), body.comment());
    }

    public record CreateRequest(@NotBlank String attemptId, @NotBlank String reason,
                                @Size(max=1000) String comment) {}
}

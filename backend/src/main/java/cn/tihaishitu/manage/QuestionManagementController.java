package cn.tihaishitu.manage;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/manage/questions")
public class QuestionManagementController {
    private final QuestionManagementStore store;
    private final QuestionManagementService service;

    public QuestionManagementController(QuestionManagementStore store, QuestionManagementService service) {
        this.store = store;
        this.service = service;
    }

    @GetMapping
    PageResult<QuestionManagementStore.QuestionView> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) Integer examYear,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) String gradingMode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String creator,
            @RequestParam(required = false) String knowledge,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        return store.search(query, subject, sourceType, examYear, questionType, gradingMode, status,
                creator, knowledge, page, Math.min(size, 100));
    }

    @GetMapping("/{id}")
    QuestionManagementStore.QuestionView get(@PathVariable String id) {
        return store.find(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "题目不存在。"));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
    QuestionManagementStore.QuestionView create(
            @Valid @RequestBody QuestionRequest request, Authentication authentication) {
        return service.create(input(request), authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
    QuestionManagementStore.QuestionView update(@PathVariable String id,
            @Valid @RequestBody QuestionRequest request, Authentication authentication) {
        if (request.expectedRevision() == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "修改题目必须携带 expectedRevision。");
        }
        return service.update(id, input(request), request.expectedRevision(), authentication);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
    QuestionManagementStore.QuestionView submit(@PathVariable String id,
            @Valid @RequestBody RevisionRequest request, Authentication authentication) {
        return service.submit(id, request.expectedRevision(), authentication);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    QuestionManagementStore.QuestionView review(@PathVariable String id,
            @Valid @RequestBody ReviewRequest request, Authentication authentication) {
        return service.review(id, request.expectedRevision(), request.approve(), request.comment(), authentication);
    }

    @PostMapping("/bulk-review")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    QuestionManagementService.BulkReviewResult bulkReview(
            @Valid @RequestBody BulkReviewRequest request, Authentication authentication) {
        return service.bulkReview(request.items().stream()
                        .map(item -> new QuestionManagementService.BulkReviewItem(item.id(), item.expectedRevision()))
                        .toList(),
                request.approve(), request.comment(), authentication);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    QuestionManagementStore.QuestionView archive(@PathVariable String id,
            @Valid @RequestBody RevisionRequest request, Authentication authentication) {
        return service.archive(id, request.expectedRevision(), authentication);
    }

    @PostMapping("/bulk-delete")
    @PreAuthorize("hasAnyRole('CONTRIBUTOR','REVIEWER','ADMIN')")
    BulkDeleteResult bulkDelete(@Valid @RequestBody BulkDeleteRequest request, Authentication authentication) {
        return new BulkDeleteResult(service.bulkDelete(request.ids(), authentication));
    }

    private static QuestionManagementStore.QuestionInput input(QuestionRequest r) {
        return new QuestionManagementStore.QuestionInput(r.subject(), r.sourceType(), r.sourceName(), r.examYear(),
                r.questionNumber(), r.questionType(), r.presentationType(), r.gradingMode(), r.content(),
                r.standardAnswer(), r.analysis() == null ? "" : r.analysis(), r.difficulty(), r.parentQuestionId(),
                r.derivationType(), r.options() == null ? List.of() : r.options().stream()
                    .map(o -> new QuestionManagementStore.OptionInput(o.key(), o.text(), o.correct(), o.sortOrder())).toList(),
                r.knowledgePoints() == null ? List.of() : r.knowledgePoints().stream()
                    .map(k -> new QuestionManagementStore.RelationInput(k.knowledgePointId(), k.role(), k.sortOrder())).toList());
    }

    public record OptionRequest(@NotBlank String key, @NotBlank String text, boolean correct, int sortOrder) {}
    public record RelationRequest(@NotBlank String knowledgePointId, @NotBlank String role, int sortOrder) {}
    public record QuestionRequest(
            @NotBlank String subject, @NotBlank String sourceType, String sourceName, Integer examYear,
            String questionNumber, @NotBlank String questionType, @NotBlank String presentationType,
            @NotBlank String gradingMode, @NotBlank String content, @NotNull JsonNode standardAnswer,
            String analysis, @Min(1) int difficulty, String parentQuestionId, String derivationType,
            List<OptionRequest> options, List<RelationRequest> knowledgePoints, Long expectedRevision) {}
    public record RevisionRequest(@NotNull Long expectedRevision) {}
    public record ReviewRequest(@NotNull Long expectedRevision, boolean approve, String comment) {}
    public record BulkReviewItemRequest(@NotBlank String id, @NotNull Long expectedRevision) {}
    public record BulkReviewRequest(
            @NotNull @Size(min = 1, max = 100) List<@Valid BulkReviewItemRequest> items,
            boolean approve, String comment) {}
    public record BulkDeleteRequest(@NotNull List<@NotBlank String> ids) {}
    public record BulkDeleteResult(int deleted) {}
}

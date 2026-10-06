package cn.tihaishitu.learning;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/learner")
public class LearnerPracticeController {
    private final LearnerPracticeService practice;

    public LearnerPracticeController(LearnerPracticeService practice) { this.practice = practice; }

    @GetMapping("/wrong-questions")
    List<LearnerPracticeStore.WrongQuestion> wrongQuestions() { return practice.wrongQuestions(); }

    @DeleteMapping("/wrong-questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeWrongQuestion(@PathVariable String questionId) { practice.removeWrongQuestion(questionId); }

    @PostMapping("/practice-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    LearnerPracticeService.SessionView start(@RequestBody LearnerPracticeService.StartRequest request) {
        return practice.start(request);
    }

    @GetMapping("/practice-sessions/active-chapter")
    ResponseEntity<LearnerPracticeService.SessionView> activeChapter() {
        var session = practice.latestChapter();
        return session == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(session);
    }

    @GetMapping("/practice-sessions/{id}")
    LearnerPracticeService.SessionView get(@PathVariable String id) { return practice.get(id); }

    @PostMapping("/practice-sessions/{id}/answers")
    LearnerPracticeService.SessionView answer(@PathVariable String id, @Valid @RequestBody AnswerRequest request) {
        return practice.answer(id, request.attemptId(), request.questionId(), request.answer());
    }

    @PostMapping("/practice-sessions/{id}/reveal")
    LearnerPracticeService.SessionView reveal(@PathVariable String id, @Valid @RequestBody AttemptRequest request) {
        return practice.reveal(id, request.attemptId(), request.questionId());
    }

    @PostMapping("/practice-sessions/{id}/self-assess")
    LearnerPracticeService.SessionView selfAssess(@PathVariable String id,
                                                  @Valid @RequestBody SelfAssessmentRequest request) {
        return practice.selfAssess(id, request.attemptId(), request.questionId(), request.assessment());
    }

    @PostMapping("/practice-sessions/{id}/next")
    LearnerPracticeService.SessionView next(@PathVariable String id) { return practice.next(id); }

    @PostMapping("/practice-sessions/{id}/end")
    LearnerPracticeService.SessionView end(@PathVariable String id) { return practice.end(id); }

    public record AttemptRequest(@NotBlank String attemptId, @NotBlank String questionId) {}
    public record AnswerRequest(@NotBlank String attemptId, @NotBlank String questionId, @NotNull JsonNode answer) {}
    public record SelfAssessmentRequest(@NotBlank String attemptId, @NotBlank String questionId,
                                        @NotBlank String assessment) {}
}

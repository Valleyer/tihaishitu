package cn.tihaishitu.game;

import jakarta.validation.constraints.NotBlank;

public record SelfAssessmentRequest(
        @NotBlank String attemptId,
        @NotBlank String questionId,
        @NotBlank String assessment) {}

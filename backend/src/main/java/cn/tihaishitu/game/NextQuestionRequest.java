package cn.tihaishitu.game;

import jakarta.validation.constraints.NotBlank;

public record NextQuestionRequest(@NotBlank String attemptId, boolean reviewOnly) {}

package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AnswerRequest(
        @NotBlank String attemptId,
        @NotBlank String questionId,
        @NotNull JsonNode answer
) {}


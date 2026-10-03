package cn.tihaishitu.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record HistoryQuestionsRequest(
        @NotNull @Size(max = 500) List<@NotBlank String> attemptIds) {}

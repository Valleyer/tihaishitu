package cn.tihaishitu.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record HistoryQuestionsRequest(@NotNull List<@NotBlank String> attemptIds) {}

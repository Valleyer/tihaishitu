package cn.tihaishitu.game;

import jakarta.validation.constraints.NotBlank;

public record IdRequest(@NotBlank String id) {}


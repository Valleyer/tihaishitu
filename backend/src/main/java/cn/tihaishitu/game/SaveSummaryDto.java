package cn.tihaishitu.game;

import java.time.Instant;

public record SaveSummaryDto(
        String id,
        String name,
        String title,
        int total,
        Instant updatedAt
) {}

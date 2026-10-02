package cn.tihaishitu.game;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record NewGameRequest(
        @NotBlank(message = "姓名不能为空")
        @Size(max = 12, message = "姓名不能超过 12 个字")
        String name,
        String gender,
        String origin,
        List<String> bankIds,
        Map<String, Integer> weights,
        String pace,
        String difficulty
) {
    public NewGameRequest {
        bankIds = bankIds == null ? List.of() : List.copyOf(bankIds);
        weights = weights == null ? Map.of() : Map.copyOf(weights);
    }
}

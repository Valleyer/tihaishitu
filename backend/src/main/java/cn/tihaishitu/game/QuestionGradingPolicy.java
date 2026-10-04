package cn.tihaishitu.game;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

public final class QuestionGradingPolicy {
    private QuestionGradingPolicy() {}

    public static boolean matches(JsonNode expected, JsonNode actual) {
        if (expected.isArray() && actual.isArray()) {
            Set<String> left = new HashSet<>();
            Set<String> right = new HashSet<>();
            expected.forEach(value -> left.add(value.asText()));
            actual.forEach(value -> right.add(value.asText()));
            return left.equals(right);
        }
        return expected.equals(actual);
    }
}

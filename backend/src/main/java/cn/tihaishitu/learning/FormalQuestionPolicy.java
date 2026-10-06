package cn.tihaishitu.learning;

import java.util.Set;

public final class FormalQuestionPolicy {
    public static final Set<String> SUPPORTED_TYPES = Set.of(
            "single_choice", "multiple_choice", "true_false", "solution");

    private FormalQuestionPolicy() {}

    public static String published(String alias) {
        return alias + ".status='published' AND " + alias + ".parent_question_id IS NULL"
                + " AND " + alias + ".question_type IN ('single_choice','multiple_choice','true_false','solution')";
    }

    public static String anyStatus(String alias) {
        return alias + ".parent_question_id IS NULL"
                + " AND " + alias + ".question_type IN ('single_choice','multiple_choice','true_false','solution')";
    }
}

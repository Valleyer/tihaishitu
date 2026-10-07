package cn.tihaishitu.learning;

/**
 * 题号显示格式化的唯一实现。
 *
 * <p>数据库里的 {@code question_resource.question_number} 可能同时存在两种写法：</p>
 *
 * <pre>
 * 纯题号        3 / 16
 * 年份前缀题号  2014-1 / 2022-16
 * </pre>
 *
 * <p>UI 一律使用 {@link #display(String, Integer)} 的结果，不要直接显示原始值；
 * 原始值保留在 API 的 {@code questionNumber} 字段里作为数据事实。</p>
 *
 * <p>只有题号确实以“同一年份 + 连字符”开头时才剥离年份，不做任意四位数字的
 * 正则替换，避免误删合法题号（例如 {@code A-3}、{@code 3(1)}、{@code 21A} 原样保留）。</p>
 */
public final class QuestionNumberFormatter {
    private QuestionNumberFormatter() {}

    /** 原始题号 → UI 显示题号；无法安全解析时原样保留，不猜。 */
    public static String display(String questionNumber, Integer examYear) {
        String raw = trimToNull(questionNumber);
        if (raw == null) return null;
        if (examYear == null) return raw;
        String prefix = examYear + "-";
        if (!raw.startsWith(prefix)) return raw;
        String stripped = trimToNull(raw.substring(prefix.length()));
        // 形如 "2014-" 的残缺值没有可用题号，保留原始值而不是显示空。
        return stripped == null ? raw : stripped;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

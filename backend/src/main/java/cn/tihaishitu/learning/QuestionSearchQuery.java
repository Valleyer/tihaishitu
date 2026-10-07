package cn.tihaishitu.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Question 搜索词的唯一解析实现（Management 与 Learning Browse 共用）。
 *
 * <p>管理员与学习者都会直接输入“年份-题号”这种结构化搜索词，例如
 * {@code 2020-7}、{@code 2020 - 7}、{@code 2020—7}、{@code 2020–7}。
 * 数据库里的事实却是 {@code exam_year = 2020} 与
 * {@code question_number = "7"}（历史数据也可能是 {@code "2020-7"}）；
 * 只做 {@code question_number LIKE '%2020-7%'} 会漏掉标准写法。</p>
 *
 * <p>因此解析规则集中在这里，两个调用方只共用同一份解析结果，
 * SQL 的 WHERE 片段各自维护（Management 查全状态、Learner 查 published）。</p>
 *
 * <pre>
 * "2020-7"    → year=2020, number="7",  parts=["2020","7"]
 * "2020 - 7"  → year=2020, number="7",  parts=["2020","7"]
 * "极限"       → year=null, number=null, parts=[]
 * "7"         → year=null, number=null, parts=[]
 * </pre>
 *
 * <p>兼容输入里的全角破折号，让用中文输入法的用户也能命中。</p>
 */
public record QuestionSearchQuery(String raw, String keyword, Integer year, String number, List<String> parts) {
    /** 四位年份 + 连接符 + 题号。连接符接受 ASCII 与常见 Unicode 破折号。 */
    private static final Pattern YEAR_NUMBER =
            Pattern.compile("^\\s*(\\d{4})\\s*[-\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015]\\s*(.+?)\\s*$");

    public QuestionSearchQuery {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public static QuestionSearchQuery parse(String query) {
        if (query == null || query.isBlank()) {
            return new QuestionSearchQuery(query, null, null, null, List.of());
        }
        String trimmed = query.trim();
        Matcher matcher = YEAR_NUMBER.matcher(trimmed);
        if (!matcher.matches()) {
            return new QuestionSearchQuery(trimmed, trimmed, null, null, List.of());
        }
        Integer year = Integer.valueOf(matcher.group(1));
        String number = matcher.group(2).trim();
        if (number.isEmpty()) {
            // 形如 "2020-" 的残缺输入不做结构化解释，退化为普通关键词。
            return new QuestionSearchQuery(trimmed, trimmed, null, null, List.of());
        }
        List<String> parts = new ArrayList<>(2);
        parts.add(matcher.group(1));
        parts.add(number);
        return new QuestionSearchQuery(trimmed, trimmed, year, number, parts);
    }

    /** 是否是“年份-题号”结构化搜索。 */
    public boolean structured() {
        return year != null && number != null;
    }

    /** 大小写无关的 LIKE 参数，调用方仍需对目标列使用 LOWER()。 */
    public String likePattern() {
        return keyword == null ? null : "%" + keyword.toLowerCase() + "%";
    }

    /** 结构化搜索时要额外匹配的历史写法 {@code "2020-7"}（大小写无关）。 */
    public String yearNumberLiteral() {
        return structured() ? (year + "-" + number).toLowerCase() : null;
    }
}

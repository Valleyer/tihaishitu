package cn.tihaishitu.learning;

/**
 * Question 列表排序的共享 SQL 片段（Management 与 Learning Browse 共用）。
 *
 * <p>管理员与学习者看到的默认顺序都是“用户可理解的题库顺序”：</p>
 *
 * <pre>
 * 1. 来源
 * 2. exam_year
 * 3. question_number 自然排序
 * 4. question_id 稳定兜底
 * </pre>
 *
 * <p>题号必须自然排序（{@code 1 &lt; 2 &lt; 7 &lt; 10 &lt; 22}），不能字符串排序出
 * {@code 1, 10, 2}。生产数据库兼容 MySQL 5.7，因此只使用
 * {@code CASE / SUBSTRING / LOCATE / LEFT / CHAR_LENGTH / REPLACE / CONCAT / COALESCE / LPAD}
 * 组成 DB 级稳定 sort key：不使用 Window Function、{@code REGEXP}/{@code REGEXP_LIKE}
 * （MySQL 5.7 没有 {@code REGEXP_LIKE} 函数，且两种引擎的正则方言不一致），
 * 也不依赖“字符串与字母比较”这类隐式类型转换；更不能只在取出一页之后用 Java 排序。</p>
 *
 * <h2>年份前缀剥离规则</h2>
 *
 * <p>历史数据里同一道题可能写成两种事实：{@code exam_year=2020 + question_number="7"}
 * 与 {@code exam_year=2020 + question_number="2020-7"}。二者必须得到同一个自然题号 7，
 * 否则历史写法会被排到 {@code 1, 2, 7, 10, 22} 之后。规则与
 * {@link QuestionNumberFormatter} 一致：</p>
 *
 * <pre>
 * 2020 + "7"       → 7
 * 2020 + "2020-7"  → 7      只有题号确实带“相同 exam_year 前缀”时才剥离年份
 * 2021 + "2020-7"  → 非数字 年份前缀不一致，不能剥掉 2020
 * 2020 + "A-3"     → 非数字 不猜数字，进入最后一档
 * 2020 + "21A"     → 非数字
 * 2020 + "3(1)"    → 非数字
 * </pre>
 *
 * <p>绝不实现成“有连接符就取后半段”，否则 {@code A-3} 会被错误当成数字 3。
 * Unicode 破折号（{@code ‐ ‑ ‒ – — ―}）在判断前缀之前先归一为 ASCII {@code -}。</p>
 *
 * <p>本类只拼接 SQL 片段，所有入参都是代码内固定的列表达式，不含用户输入。</p>
 */
public final class QuestionNumberSort {
    /** 自然排序键的数字补零宽度；10 位足够覆盖任何题号。 */
    private static final int NUMBER_WIDTH = 10;

    private QuestionNumberSort() {}

    /**
     * 题号的自然排序键。
     *
     * <pre>
     * "1" / "2" / "7" / "10" / "22" → "10000000001" … "10000000022"
     * "2020-7"（exam_year=2020）    → "10000000007"   与 "7" 相同的数字事实
     * 无题号 / "A-3" / "21A" / "3(1)" / 年份不一致的年份前缀题号 → "20000000000"
     * </pre>
     *
     * @param questionNumberExpression {@code question_resource.question_number} 列表达式
     * @param examYearExpression       {@code question_resource.exam_year} 列表达式
     */
    public static String naturalKey(String questionNumberExpression, String examYearExpression) {
        String head = head(questionNumberExpression, examYearExpression);
        String numericHead = "CASE WHEN CHAR_LENGTH(" + head + ") > 0"
                + " AND (" + stripDigits(head) + ") = '' THEN " + head + " ELSE '0' END";
        // '0' 不是有效题号：纯数字判断通过时仍会得到 '0'，统一当作“没有数字事实”。
        return "CONCAT(CASE WHEN " + numericHead + " = '0' THEN '2' ELSE '1' END,"
                + " LPAD(" + numericHead + ", " + NUMBER_WIDTH + ", '0'))";
    }

    /**
     * 单一稳定排序键：来源 → 年份 → 题号自然排序 → question_id。
     *
     * <p>来源使用用户可见的解析后名称，与列表展示一致；没有 exam_year 的历史题排在最后
     * （{@code 9999}）。各段都有固定宽度或用 {@code '|'} 分隔，因此整个键的字符串排序
     * 结果与逐字段排序一致。</p>
     */
    public static String orderBy(String sourceNameExpression, String examYearExpression,
                                 String questionNumberExpression, String questionIdExpression) {
        return "CONCAT(COALESCE(" + sourceNameExpression + ",'')"
                + ", '|', LPAD(COALESCE(" + examYearExpression + ",9999), 4, '0')"
                + ", '|', " + naturalKey(questionNumberExpression, examYearExpression)
                + ", '|', COALESCE(" + questionIdExpression + ",''))";
    }

    /**
     * 归一后的“待比较题号片段”。
     *
     * <pre>
     * 无题号 / 只有年份前缀（"2020-"）      → ''
     * 与 exam_year 同年份的前缀题号         → 去掉 "&lt;year&gt;-" 之后的部分
     * 前缀年份与 exam_year 不一致，或没有连接符 → 原值（随后按“是否纯数字”分档）
     * </pre>
     *
     * <p>用 {@code LOCATE} + {@code SUBSTRING} + {@code LEFT} 表达，不用
     * {@code SUBSTRING_INDEX}（H2 的 MySQL 模式没有这个函数）。</p>
     */
    private static String head(String questionNumberExpression, String examYearExpression) {
        String value = normalized(questionNumberExpression);
        String prefix = "CONCAT(" + examYearExpression + ",'-')";
        String prefixLength = "CHAR_LENGTH(" + prefix + ")";
        // 只有“连接符之后还有内容”时才是年份前缀写法。
        String hasSuffix = "CHAR_LENGTH(" + value + ") > " + prefixLength;
        String exactlySameYearPrefix = value + " = " + prefix;
        String stableSameYearPrefix = hasSuffix + " AND " + prefix + " = LEFT(" + value + ", " + prefixLength + ")";
        String mismatchedYearPrefix = "LOCATE('-', " + value + ") > 1 AND " + value + " <> " + prefix
                + " AND " + prefix + " <> LEFT(" + value + ", " + prefixLength + ")";
        return "CASE WHEN " + value + " IS NULL OR " + value + " = '' THEN ''"
                + " WHEN " + exactlySameYearPrefix + " THEN ''"
                + " WHEN " + stableSameYearPrefix + " THEN SUBSTRING(" + value + ", " + prefixLength + " + 1)"
                + " WHEN " + mismatchedYearPrefix + " THEN ''"
                + " ELSE " + value + " END";
    }

    /** 先去掉首尾空白对齐 {@link QuestionNumberFormatter}，再把 Unicode 破折号归一为 ASCII {@code -}。 */
    private static String normalized(String expression) {
        String value = "TRIM(COALESCE(" + expression + ",''))";
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < DASHES.length; index++) normalized.append("REPLACE(");
        normalized.append(value);
        for (String dash : DASHES) normalized.append(",'").append(dash).append("','-')");
        return normalized.toString();
    }

    /**
     * 去掉 head 里的全部数字字符：结果为空串说明 head 只由数字组成。
     * 这是 MySQL 5.7 / H2 MySQL 模式都能执行的“是否纯数字”判断。
     */
    private static String stripDigits(String head) {
        StringBuilder expression = new StringBuilder();
        for (char digit = '0'; digit <= '9'; digit++) expression.append("REPLACE(");
        expression.append(head);
        for (char digit = '9'; digit >= '0'; digit--) expression.append(",'").append(digit).append("','')");
        return expression.toString();
    }

    /** 需要归一的 Unicode 破折号：连字符、非断行连字符、短破折号、长破折号、横线。 */
    private static final String[] DASHES = {"\u2010", "\u2011", "\u2012", "\u2013", "\u2014", "\u2015"};
}

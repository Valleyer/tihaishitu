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
 * {@code CASE / SUBSTRING / LOCATE / CHAR_LENGTH / REPLACE / CONCAT / COALESCE / LPAD}
 * 组成 DB 级稳定 sort key：不使用 Window Function，不使用 {@code REGEXP}
 * （H2 支持、MySQL 5.7 也能用，但两种引擎的正则方言并不一致），
 * 也不依赖“字符串与字母比较”这类隐式类型转换；更不能只在取出一页之后用 Java 排序。</p>
 *
 * <p>历史数据里题号可能是 {@code "7"}，也可能是 {@code "2020-7"}：两种写法都取
 * “连接符前的纯数字片段”，因此 {@code "2020-7"} 与 {@code "7"} 得到同一个数字 7，
 * 顺序再由来源 / 年份 / question_id 稳定决定。非纯数字题号（{@code A-3}、
 * {@code 21A}、{@code 3(1)}）没有可比较的数字事实，统一归入最后一档并按
 * {@code question_id} 兜底，不会因为字符串排序把它们插进 1–22 之间。</p>
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
     * "2020-7" / "2020—7"           → "10000000007"   与 "7" 相同数字事实
     * 无题号 / "A-3" / "21A" / "0"  → "20000000000"   归入最后一档
     * </pre>
     */
    public static String naturalKey(String expression) {
        String head = head(expression);
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
                + ", '|', " + naturalKey(questionNumberExpression)
                + ", '|', COALESCE(" + questionIdExpression + ",''))";
    }

    /**
     * 题号的“连接符前片段”：{@code "2020-7" → "2020"}，{@code "A-3" → "A"}，
     * 没有连接符时就是整个值。用 {@code LOCATE} 定位，不用 {@code SUBSTRING_INDEX}
     * （H2 的 MySQL 模式没有这个函数）。
     */
    private static String head(String expression) {
        String value = "COALESCE(" + expression + ",'')";
        return "CASE WHEN LOCATE('-', " + value + ") > 1"
                + " THEN SUBSTRING(" + value + ", 1, LOCATE('-', " + value + ") - 1)"
                + " WHEN LOCATE('-', " + value + ") = 1 THEN ''"
                + " ELSE " + value + " END";
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
}

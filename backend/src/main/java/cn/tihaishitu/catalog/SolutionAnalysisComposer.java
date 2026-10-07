package cn.tihaishitu.catalog;

/** V20 与 v3 adapter 共用的旧综合题内容合并格式。 */
public final class SolutionAnalysisComposer {
    private SolutionAnalysisComposer() {}

    public static String merge(String legacyAnswer, String analysis) {
        String answer = legacyAnswer == null ? "" : legacyAnswer.strip();
        String detail = analysis == null ? "" : analysis.strip();
        if (answer.isEmpty()) return detail;
        if (detail.isEmpty()) return "## 参考答案\n\n" + answer;
        return "## 参考答案\n\n" + answer + "\n\n## 解析\n\n" + detail;
    }
}

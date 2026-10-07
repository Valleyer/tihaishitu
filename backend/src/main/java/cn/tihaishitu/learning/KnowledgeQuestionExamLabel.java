package cn.tihaishitu.learning;

/**
 * 真题展示标签的唯一生成规则。
 *
 * <p>真题年份的事实来源是 {@code question_resource.exam_year}，科目是
 * {@code question_resource.subject_name}。展示标签在读取时动态生成，
 * <b>不为显示文字新增 tag 表</b>，也不把年份建成 KnowledgePoint。</p>
 *
 * <p>示例：数学一 + 2021 → {@code 2021年考研数学一真题}；
 * 408 + 2024 → {@code 2024年408考研真题}。</p>
 */
public final class KnowledgeQuestionExamLabel {
    /** 数据库中的正式科目名 → 标签中的科目短名。 */
    private static final String SUBJECT_408 = "408计算机学科专业基础";
    private static final String LABEL_408 = "408考研";
    private static final String SUBJECT_MATH1 = "数学一";
    private static final String LABEL_MATH1 = "考研数学一";

    private KnowledgeQuestionExamLabel() {}

    public static String generate(String subjectName, Integer examYear) {
        if (examYear == null) return null;
        String subject = label(subjectName);
        if (subject == null) return null;
        return examYear + "年" + subject + "真题";
    }

    /** 科目短名；未知科目不猜，返回 null 表示不生成真题标签。 */
    public static String label(String subjectName) {
        if (subjectName == null) return null;
        String value = subjectName.trim();
        if (value.isEmpty()) return null;
        if (value.contains("408")) return LABEL_408;
        if (SUBJECT_408.equals(value)) return LABEL_408;
        if (SUBJECT_MATH1.equals(value)) return LABEL_MATH1;
        return null;
    }

    /**
     * 题面标题：{@code 2022年考研数学一真题 · 第3题}。
     * 缺少年份或题号时降级为能确定的部分，不拼出半截假信息。
     */
    public static String title(String subjectName, Integer examYear, String questionNumber) {
        String label = generate(subjectName, examYear);
        String number = questionNumber == null || questionNumber.isBlank() ? null : questionNumber.trim();
        if (label == null) return number == null ? null : "第" + number + "题";
        return number == null ? label : label + " · 第" + number + "题";
    }
}

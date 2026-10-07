package cn.tihaishitu.learning;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CHAPTER 正式选题策略：整个 Chapter 有一条确定的 Question sequence。
 *
 * <p>顺序规则（不再“KP 顺序 + KP 内随机”）：</p>
 *
 * <pre>
 * 1. Chapter 内 KnowledgePoint 的 question_bank_knowledge.sort_order
 * 2. 同 KP 内按稳定 Source identity 分组（优先 source_id；legacy 才回退 source_type + source_name）
 * 3. exam_year
 * 4. question_number 自然排序（1 &lt; 2 &lt; 7 &lt; 10 &lt; 22）
 * 5. question_id 稳定兜底
 * </pre>
 *
 * <p>Source 排序绝不使用 {@code display_name}，因为 displayName 是可修改的展示事实。
 * 同一道 Question 关联 Chapter 内多个 KP 时只出现一次，归属到按 KP 顺序第一次遇到它的位置，
 * 该位置的 KP 就是本次 Attempt 冻结的 {@code targetKnowledgePointId}。</p>
 *
 * <p>cursor 粒度是 Learner × Book × Chapter，不新增状态表：从“最近一次 graded 的 chapter Attempt”
 * 推导。只创建 / reveal 但未 graded 不推进；末尾 wrap 到第一题；历史 cursor 题已经
 * archive / unbind / 删除时安全从第一题重新开始，不 500。</p>
 */
@Service
public class ChapterPracticeSelector {
    /** 确定性题序中的一道题与它冻结的 target KnowledgePoint。 */
    public record Step(String questionId, String targetKnowledgePointId) {}

    /** 一个 Chapter 的确定性题序。 */
    public record Sequence(List<Step> steps) {
        public Sequence { steps = List.copyOf(steps); }
        public boolean isEmpty() { return steps.isEmpty(); }
        public int size() { return steps.size(); }

        public int indexOf(String questionId) {
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).questionId().equals(questionId)) return index;
            }
            return -1;
        }

        /**
         * cursor 的下一题：末尾 wrap 到第一题；cursor 为空或已不在当前题序时从第一题重新开始。
         * Chapter 是连续练习模式，到序列末尾不会永久 complete，用户可以手动结束 Session。
         */
        public Optional<Step> successorOf(String cursorQuestionId) {
            if (steps.isEmpty()) return Optional.empty();
            int index = cursorQuestionId == null ? -1 : indexOf(cursorQuestionId);
            return Optional.of(steps.get(index < 0 ? 0 : (index + 1) % steps.size()));
        }
    }

    private final PracticeSelectionStore store;

    public ChapterPracticeSelector(PracticeSelectionStore store) { this.store = store; }

    /**
     * 当前冻结 scope 下的确定性题序。
     *
     * <p>题序只依赖传入的 {@code allowedKnowledgePointIds}（Session 冻结 scope），
     * 不读取实时 {@code learner_selected_book}：已经开始的 active Session 不会因为
     * Learner 在别处修改学习范围而换题池。</p>
     */
    public Sequence sequence(String bookId, String chapterId, Set<String> allowedKnowledgePointIds) {
        Map<String, PracticeSelectionStore.ChapterSequenceRow> firstOccurrence = new LinkedHashMap<>();
        for (PracticeSelectionStore.ChapterSequenceRow row
                : store.chapterSequence(bookId, chapterId, allowedKnowledgePointIds)) {
            // 已按 KP 顺序读回，putIfAbsent 保留“按 KP 顺序第一次遇到它”的位置与 target KP。
            firstOccurrence.putIfAbsent(row.questionId(), row);
        }
        return new Sequence(firstOccurrence.values().stream().sorted(ORDER)
                .map(row -> new Step(row.questionId(), row.knowledgePointId())).toList());
    }

    /**
     * 新开 Chapter Session 与 Session 内 next 使用同一套推导：
     * cursor = 最近一次 graded 的题，取其 successor。
     */
    public Optional<Step> next(String learnerId, String bookId, String chapterId,
                               Set<String> allowedKnowledgePointIds) {
        Sequence sequence = sequence(bookId, chapterId, allowedKnowledgePointIds);
        if (sequence.isEmpty()) return Optional.empty();
        String cursor = store.latestGradedChapterQuestionId(learnerId, bookId, chapterId).orElse(null);
        return sequence.successorOf(cursor);
    }

    private static final Comparator<PracticeSelectionStore.ChapterSequenceRow> ORDER = Comparator
            .comparingInt(PracticeSelectionStore.ChapterSequenceRow::knowledgePointSortOrder)
            .thenComparing(PracticeSelectionStore.ChapterSequenceRow::knowledgePointId,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ChapterPracticeSelector::sourceIdentity,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(PracticeSelectionStore.ChapterSequenceRow::examYear,
                    Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
            .thenComparing(PracticeSelectionStore.ChapterSequenceRow::questionNumber,
                    ChapterPracticeSelector::naturalCompare)
            .thenComparing(PracticeSelectionStore.ChapterSequenceRow::questionId,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * 稳定 Source identity：优先 {@code source_id}；legacy 无 source_id 时才
     * 回退 {@code source_type + source_name}。绝不使用 display_name。
     */
    static String sourceIdentity(PracticeSelectionStore.ChapterSequenceRow row) {
        if (row.sourceId() != null && !row.sourceId().isBlank()) return "1:" + row.sourceId();
        return "0:" + nullToEmpty(row.sourceType()) + "\u0000" + nullToEmpty(row.sourceName());
    }

    /**
     * 题号自然排序：数字片段按数值比较，因此 {@code 1 < 2 < 7 < 10 < 22}，
     * 不会字符串排序出 {@code 1,10,2}；空题号排在最后。
     */
    static int naturalCompare(String left, String right) {
        boolean leftBlank = left == null || left.isBlank();
        boolean rightBlank = right == null || right.isBlank();
        if (leftBlank || rightBlank) return leftBlank && rightBlank ? 0 : leftBlank ? 1 : -1;
        int leftIndex = 0, rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            char leftChar = left.charAt(leftIndex), rightChar = right.charAt(rightIndex);
            if (Character.isDigit(leftChar) && Character.isDigit(rightChar)) {
                int leftStart = leftIndex, rightStart = rightIndex;
                while (leftIndex < left.length() && Character.isDigit(left.charAt(leftIndex))) leftIndex++;
                while (rightIndex < right.length() && Character.isDigit(right.charAt(rightIndex))) rightIndex++;
                String leftNumber = trimLeadingZeros(left.substring(leftStart, leftIndex));
                String rightNumber = trimLeadingZeros(right.substring(rightStart, rightIndex));
                int compared = Integer.compare(leftNumber.length(), rightNumber.length());
                if (compared != 0) return compared;
                compared = leftNumber.compareTo(rightNumber);
                if (compared != 0) return compared;
            } else {
                int compared = Character.compare(leftChar, rightChar);
                if (compared != 0) return compared;
                leftIndex++;
                rightIndex++;
            }
        }
        return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
    }

    private static String trimLeadingZeros(String digits) {
        int index = 0;
        while (index < digits.length() - 1 && digits.charAt(index) == '0') index++;
        return digits.substring(index);
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}

package cn.tihaishitu.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PR3 Practice Selection V2 的公共候选事实查询。
 *
 * <p>四套正式选题策略共享这里的“事实”，但都不共享“决定下一题是谁”的逻辑：</p>
 *
 * <pre>
 * RANDOM    每日额度（draw_mode='random'）、KP 内 lane 计数、oldest 的 last graded、active 永久错题
 * CHAPTER   Chapter 内确定性题序、跨 Session 的 graded cursor
 * KNOWLEDGE 复用 KnowledgeQuestionPoolService 的 KP 候选口径
 * WRONG     复用 LearnerPracticeStore 的 active 错题口径
 * </pre>
 *
 * <p>本类只读，不写任何状态；lane 与 cursor 都不新增状态表，全部从既有事实推导。</p>
 */
@Repository
public class PracticeSelectionStore {
    /** KP × published Formal Parent Question（core + auxiliary 都算覆盖）。 */
    public record KnowledgeCandidate(String knowledgePointId, String questionId) {}

    /** 上一个 RANDOM 正式 Attempt 的目标知识点与判题结果。 */
    public record LastRandomAttempt(String knowledgePointId, String assessment) {}

    /** Chapter 确定性题序的原始行（未去重、未排序）。 */
    public record ChapterSequenceRow(String knowledgePointId, int knowledgePointSortOrder, String questionId,
                                     String sourceId, String sourceType, String sourceName,
                                     Integer examYear, String questionNumber) {}

    private final JdbcTemplate jdbc;

    public PracticeSelectionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 当天已经 RANDOM 发出过的 Question。
     *
     * <p>题目一经发出就占用当天额度，所以 {@code active / revealed / graded} 全部算数，
     * 不能等 {@code answered_at} 才去重。窗口为 {@code [fromInclusive, toExclusive)}，
     * 由 {@link PracticeBusinessDay} 按 Asia/Shanghai 计算。</p>
     */
    public Set<String> randomQuestionIdsBetween(String learnerId, Instant fromInclusive, Instant toExclusive) {
        if (learnerId == null) return Set.of();
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT question_id FROM study_attempt
                 WHERE learner_id = ? AND draw_mode = 'random'
                   AND created_at >= ? AND created_at < ?
                """, (row, index) -> row.getString(1), learnerId,
                Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));
    }

    /**
     * 当前允许范围内全部 KP × Formal Parent Question 关系。
     *
     * <p>口径与 KnowledgePoint 专项候选完全一致（{@code question_resource_knowledge}
     * 的 core / auxiliary 关系 + published Formal Parent），因此 RANDOM 的
     * “这个 KP 今天还有题可选吗”与专项练到的是同一批题。</p>
     */
    public List<KnowledgeCandidate> knowledgeCandidates(Set<String> allowedKnowledgePointIds) {
        if (allowedKnowledgePointIds == null || allowedKnowledgePointIds.isEmpty()) return List.of();
        List<String> points = List.copyOf(allowedKnowledgePointIds);
        return jdbc.query("""
                SELECT DISTINCT rel.knowledge_point_id, q.id
                  FROM question_resource q
                  JOIN question_resource_knowledge rel ON rel.question_id = q.id
                  JOIN global_knowledge_point k ON k.id = rel.knowledge_point_id
                 WHERE rel.knowledge_point_id IN (%s)
                   AND k.status = 'active'
                   AND %s
                   AND %s
                 ORDER BY rel.knowledge_point_id, q.id
                """.formatted(placeholders(points.size()),
                        KnowledgeQuestionCoveragePolicy.anyRelationRole("rel"),
                        FormalQuestionPolicy.published("q")),
                (row, index) -> new KnowledgeCandidate(row.getString(1), row.getString(2)), points.toArray());
    }

    /**
     * Learner 最近一次 RANDOM 正式 Attempt 的 target KP 与 assessment。
     * 旧 Attempt 没有 draw_mode，不会被读成 RANDOM。
     */
    public Optional<LastRandomAttempt> lastRandomAttempt(String learnerId) {
        if (learnerId == null) return Optional.empty();
        return jdbc.query("""
                SELECT target_knowledge_point_id, assessment FROM study_attempt
                 WHERE learner_id = ? AND draw_mode = 'random'
                 ORDER BY created_at DESC, id DESC LIMIT 1
                """, (row, index) -> new LastRandomAttempt(row.getString(1), row.getString(2)), learnerId)
                .stream().findFirst();
    }

    /**
     * 该 Learner 在该 target KnowledgePoint 下已经创建的 RANDOM Attempt 数。
     *
     * <p>RANDOM 的 oldest / wrong 交替 lane 不新增状态表：偶数走 oldest，奇数走 wrong。
     * Attempt 一经创建就消费一个 lane slot（含 active / revealed），
     * 所以 wrong lane 回退 oldest 时这个 slot 同样算消费。</p>
     */
    public int randomDrawCount(String learnerId, String knowledgePointId) {
        if (learnerId == null || knowledgePointId == null) return 0;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM study_attempt
                 WHERE learner_id = ? AND draw_mode = 'random' AND target_knowledge_point_id = ?
                """, Integer.class, learnerId, knowledgePointId);
        return count == null ? 0 : count;
    }

    /**
     * 每个 Question 的“最近一次 graded”时间，跨全部正式作答模式（不只 RANDOM）。
     * 从未 graded 的题不出现在结果里，由调用方当作“无限久以前”，排在最前。
     */
    public Map<String, Instant> lastGradedAt(String learnerId, Collection<String> questionIds) {
        if (learnerId == null || questionIds == null || questionIds.isEmpty()) return Map.of();
        List<String> ids = List.copyOf(new LinkedHashSet<>(questionIds));
        Map<String, Instant> result = new LinkedHashMap<>();
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.addAll(ids);
        jdbc.query("""
                SELECT question_id, MAX(answered_at) last_graded_at
                  FROM study_attempt
                 WHERE learner_id = ? AND status = 'graded' AND answered_at IS NOT NULL
                   AND question_id IN (%s)
                 GROUP BY question_id
                """.formatted(placeholders(ids.size())), (RowCallbackHandler) row -> {
            Timestamp value = row.getTimestamp("last_graded_at");
            if (value != null) result.put(row.getString("question_id"), value.toInstant());
        }, args.toArray());
        return result;
    }

    /**
     * 永久错题本当前 active、且属于该 target KP 的正式父题。
     * 用户手动移出（status='removed'）的题立即不再进入 wrong lane。
     */
    public Set<String> activeWrongQuestionIds(String learnerId, String knowledgePointId) {
        if (learnerId == null || knowledgePointId == null) return Set.of();
        return new LinkedHashSet<>(jdbc.query("""
                SELECT wrong.question_id
                  FROM learner_wrong_question wrong
                  JOIN question_resource q ON q.id = wrong.question_id
                  JOIN global_knowledge_point k ON k.id = wrong.target_knowledge_point_id
                 WHERE wrong.learner_id = ? AND wrong.status = 'active'
                   AND wrong.target_knowledge_point_id = ?
                   AND k.status = 'active'
                   AND %s
                 ORDER BY wrong.question_id
                """.formatted(FormalQuestionPolicy.published("q")),
                (row, index) -> row.getString(1), learnerId, knowledgePointId));
    }

    /** 单题 wrong_review：该题在永久错题本中记录的 target KP。 */
    public Optional<String> activeWrongTargetKnowledgePoint(String learnerId, String questionId) {
        if (learnerId == null || questionId == null) return Optional.empty();
        return jdbc.query("""
                SELECT target_knowledge_point_id FROM learner_wrong_question
                 WHERE learner_id = ? AND question_id = ? AND status = 'active'
                """, (row, index) -> row.getString(1), learnerId, questionId).stream().findFirst();
    }

    /**
     * Chapter 的原始题序行：Chapter 内 KP（按 chapter 内 sort_order）× 该 KP 的正式父题。
     *
     * <p>只使用稳定事实：KP sort_order、question_source.source_id（legacy 无 source_id 时
     * 回退 source_type + source_name）、exam_year、question_number、question_id。
     * 不使用可修改的 {@code display_name}。去重、自然排序与 target KP 归属由
     * {@link ChapterPracticeSelector} 在 Java 内完成（MySQL 5.7 不引入 Window Function）。</p>
     */
    public List<ChapterSequenceRow> chapterSequence(String learnerId, String bookId, String chapterId,
                                                    Set<String> allowedKnowledgePointIds) {
        if (learnerId == null || bookId == null || chapterId == null
                || allowedKnowledgePointIds == null || allowedKnowledgePointIds.isEmpty()) return List.of();
        List<String> points = List.copyOf(allowedKnowledgePointIds);
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.add(bookId);
        args.add(chapterId);
        args.addAll(points);
        return jdbc.query("""
                SELECT bk.knowledge_point_id, bk.sort_order knowledge_point_sort_order,
                       q.id question_id, q.source_id, q.source_type, q.source_name,
                       q.exam_year, q.question_number
                  FROM question_bank_knowledge bk
                  JOIN learner_selected_book selected
                    ON selected.bank_id = bk.bank_id AND selected.learner_id = ?
                  JOIN global_knowledge_point k ON k.id = bk.knowledge_point_id
                  JOIN question_resource_knowledge rel ON rel.knowledge_point_id = bk.knowledge_point_id
                  JOIN question_resource q ON q.id = rel.question_id
                 WHERE bk.bank_id = ? AND bk.chapter_id = ?
                   AND bk.knowledge_point_id IN (%s)
                   AND k.status = 'active'
                   AND %s
                   AND %s
                   AND %s
                 ORDER BY bk.sort_order, bk.knowledge_point_id, q.id
                """.formatted(placeholders(points.size()),
                        TrainableKnowledge.exists("k"),
                        KnowledgeQuestionCoveragePolicy.anyRelationRole("rel"),
                        FormalQuestionPolicy.published("q")),
                (row, index) -> new ChapterSequenceRow(row.getString("knowledge_point_id"),
                        row.getInt("knowledge_point_sort_order"), row.getString("question_id"),
                        row.getString("source_id"), row.getString("source_type"), row.getString("source_name"),
                        row.getObject("exam_year", Integer.class), row.getString("question_number")),
                args.toArray());
    }

    /**
     * Chapter cursor 的事实来源：该 Learner 在同一个 Book + Chapter 下**最近一次 graded** 的题。
     *
     * <p>只创建 / reveal 但未 graded 的 Attempt 不会推进 cursor。
     * 历史 cursor 题已下架 / 解绑 / 删除时，当前题序里找不到它，由调用方从第一题安全重新开始。</p>
     */
    public Optional<String> latestGradedChapterQuestionId(String learnerId, String bookId, String chapterId) {
        if (learnerId == null || bookId == null || chapterId == null) return Optional.empty();
        return jdbc.query("""
                SELECT a.question_id
                  FROM study_attempt a
                  JOIN learner_practice_session s ON s.id = a.practice_session_id
                 WHERE a.learner_id = ? AND a.status = 'graded'
                   AND s.intent = 'chapter_drill'
                   AND s.target_book_id = ? AND s.target_chapter_id = ?
                 ORDER BY a.answered_at DESC, a.created_at DESC, a.id DESC
                 LIMIT 1
                """, (row, index) -> row.getString(1), learnerId, bookId, chapterId).stream().findFirst();
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}

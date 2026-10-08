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
 * <p>KP 内 lane 与 Chapter cursor 仍从既有事实推导；PR6 为 RANDOM 第一层
 * ALL / WRONG KP 池轮换和最近一次 RANDOM Attempt 指针持久化每个 Learner 的最小状态。</p>
 */
@Repository
public class PracticeSelectionStore {
    /** KP × published Formal Parent Question（core + auxiliary 都算覆盖）。 */
    public record KnowledgeCandidate(String knowledgePointId, String questionId) {}

    /** 上一个 RANDOM 正式 Attempt 的目标知识点与判题结果。 */
    public record LastRandomAttempt(String knowledgePointId, String assessment) {}

    public enum RequestedKnowledgePool {
        ALL("all"), WRONG("wrong");

        private final String wireValue;
        RequestedKnowledgePool(String wireValue) { this.wireValue = wireValue; }
        public String wireValue() { return wireValue; }
    }

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
     * V24 指针是升级后唯一的严格顺序事实；旧 Attempt 没有指针时，只兼容读取
     * created_at 最大且唯一的一条。最大时间同秒并列时顺序不可恢复，返回空而不按 UUID 猜测。
     */
    public Optional<LastRandomAttempt> lastRandomAttempt(String learnerId) {
        if (learnerId == null) return Optional.empty();
        List<LastRandomAttempt> current = jdbc.query("""
                SELECT attempt.target_knowledge_point_id, attempt.assessment
                  FROM learner_random_attempt_cursor cursor
                  JOIN study_attempt attempt ON attempt.id = cursor.last_random_attempt_id
                 WHERE cursor.learner_id = ? AND attempt.learner_id = cursor.learner_id
                   AND attempt.draw_mode = 'random'
                """, (row, index) -> new LastRandomAttempt(row.getString(1), row.getString(2)), learnerId);
        if (!current.isEmpty()) return Optional.of(current.get(0));

        List<LastRandomAttempt> legacy = jdbc.query("""
                SELECT attempt.target_knowledge_point_id, attempt.assessment
                  FROM study_attempt attempt
                 WHERE attempt.learner_id = ? AND attempt.draw_mode = 'random'
                   AND attempt.created_at = (
                       SELECT MAX(candidate.created_at)
                         FROM study_attempt candidate
                        WHERE candidate.learner_id = ? AND candidate.draw_mode = 'random'
                   )
                """, (row, index) -> new LastRandomAttempt(row.getString(1), row.getString(2)),
                learnerId, learnerId);
        return legacy.size() == 1 ? Optional.of(legacy.get(0)) : Optional.empty();
    }

    /**
     * Attempt 创建成功后记录严格的最近 RANDOM 指针。调用方必须持有 Learner 行锁，
     * 并与 Attempt、池轮换及 World 状态写入处于同一事务。
     */
    public void recordLastRandomAttempt(String learnerId, String attemptId) {
        int changed = jdbc.update("""
                UPDATE learner_random_attempt_cursor
                   SET last_random_attempt_id = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE learner_id = ?
                """, attemptId, learnerId);
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO learner_random_attempt_cursor(
                        learner_id, last_random_attempt_id, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """, learnerId, attemptId);
        }
    }

    /** 没有 V23 状态行的既有 Learner 从 ALL 开始；旧 Attempt 不参与猜测或回填。 */
    public RequestedKnowledgePool nextRequestedKnowledgePool(String learnerId) {
        if (learnerId == null) return RequestedKnowledgePool.ALL;
        Optional<String> last = jdbc.query("""
                SELECT last_requested_pool FROM learner_random_kp_rotation WHERE learner_id = ?
                """, (row, index) -> row.getString(1), learnerId).stream().findFirst();
        if (last.isEmpty()) return RequestedKnowledgePool.ALL;
        return RequestedKnowledgePool.WRONG.wireValue().equals(last.get())
                ? RequestedKnowledgePool.ALL : RequestedKnowledgePool.WRONG;
    }

    /**
     * 在正式 RANDOM Attempt 创建成功后、同一事务内消费池轮换槽。
     * 调用方必须先持有 learner_account 行锁；事务失败会连同 Attempt 一起回滚。
     */
    public void recordRequestedKnowledgePool(String learnerId, RequestedKnowledgePool requested) {
        int changed = jdbc.update("""
                UPDATE learner_random_kp_rotation
                   SET last_requested_pool = ?, selection_count = selection_count + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE learner_id = ?
                """, requested.wireValue(), learnerId);
        if (changed == 0) {
            jdbc.update("""
                    INSERT INTO learner_random_kp_rotation(
                        learner_id, last_requested_pool, selection_count, updated_at)
                    VALUES (?, ?, 1, CURRENT_TIMESTAMP)
                    """, learnerId, requested.wireValue());
        }
    }

    /**
     * active 永久错题通过当前有效 core/auxiliary 关系覆盖到的候选 KP。
     * 错题本历史 target 不参与；错题本身今天是否已 RANDOM 出过也不参与。
     */
    public Set<String> activeWrongKnowledgePointIds(String learnerId,
                                                    Collection<String> candidateKnowledgePointIds) {
        if (learnerId == null || candidateKnowledgePointIds == null
                || candidateKnowledgePointIds.isEmpty()) return Set.of();
        List<String> points = List.copyOf(new LinkedHashSet<>(candidateKnowledgePointIds));
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.addAll(points);
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT rel.knowledge_point_id
                  FROM learner_wrong_question wrong
                  JOIN question_resource q ON q.id = wrong.question_id
                  JOIN question_resource_knowledge rel ON rel.question_id = q.id
                  JOIN global_knowledge_point k ON k.id = rel.knowledge_point_id
                 WHERE wrong.learner_id = ? AND wrong.status = 'active'
                   AND rel.knowledge_point_id IN (%s)
                   AND k.status = 'active'
                   AND %s
                   AND %s
                 ORDER BY rel.knowledge_point_id
                """.formatted(placeholders(points.size()),
                        KnowledgeQuestionCoveragePolicy.anyRelationRole("rel"),
                        FormalQuestionPolicy.published("q")),
                (row, index) -> row.getString(1), args.toArray()));
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
     * 给出的候选题里，当前仍在永久错题本 active 的那一部分。
     *
     * <p>wrong lane 的两个事实必须分离：</p>
     *
     * <pre>
     * “属于当前 target KnowledgePoint” → question_resource_knowledge 关系（由候选题集合本身保证）
     * “是不是当前 active 错题”        → learner_wrong_question.status 按 learner + question 判断
     * </pre>
     *
     * <p>因此这里**不**要求 {@code learner_wrong_question.target_knowledge_point_id} 等于当前 KP：
     * 一道同时关联 K1 / K2 的题若是在 K2 下做错的，进入 K1 的 wrong lane 时仍然是候选。
     * {@code target_knowledge_point_id} 继续用于错题本原始归因与 wrong_review / wrong_drill。
     * 用户手动移出（status='removed'）后，所有 KP 的 wrong lane 都立即排除它。</p>
     */
    public Set<String> activeWrongQuestionIds(String learnerId, Collection<String> candidateQuestionIds) {
        if (learnerId == null || candidateQuestionIds == null || candidateQuestionIds.isEmpty()) return Set.of();
        List<String> ids = List.copyOf(new LinkedHashSet<>(candidateQuestionIds));
        List<Object> args = new ArrayList<>();
        args.add(learnerId);
        args.addAll(ids);
        return new LinkedHashSet<>(jdbc.query("""
                SELECT question_id FROM learner_wrong_question
                 WHERE learner_id = ? AND status = 'active'
                   AND question_id IN (%s)
                 ORDER BY question_id
                """.formatted(placeholders(ids.size())),
                (row, index) -> row.getString(1), args.toArray()));
    }

    /**
     * Chapter 的原始题序行：Chapter 内 KP（按 chapter 内 sort_order）× 该 KP 的正式父题。
     *
     * <p>只使用稳定事实：KP sort_order、question_source.source_id（legacy 无 source_id 时
     * 回退 source_type + source_name）、exam_year、question_number、question_id。
     * 不使用可修改的 {@code display_name}。去重、自然排序与 target KP 归属由
     * {@link ChapterPracticeSelector} 在 Java 内完成（MySQL 5.7 不引入 Window Function）。</p>
     *
     * <p>这里**不**读取实时的 {@code learner_selected_book}：调用方传入的
     * {@code allowedKnowledgePointIds} 就是该 Session 冻结的 scope，
     * 因此已经开始的 active Session 不会因为 Learner 在别处改了学习范围而换题池。
     * “文集是否在当前学习范围内”由 {@code LearnerPracticeService.startChapter} 的入口校验负责。</p>
     */
    public List<ChapterSequenceRow> chapterSequence(String bookId, String chapterId,
                                                    Set<String> allowedKnowledgePointIds) {
        if (bookId == null || chapterId == null
                || allowedKnowledgePointIds == null || allowedKnowledgePointIds.isEmpty()) return List.of();
        List<String> points = List.copyOf(allowedKnowledgePointIds);
        List<Object> args = new ArrayList<>();
        args.add(bookId);
        args.add(chapterId);
        args.addAll(points);
        return jdbc.query("""
                SELECT bk.knowledge_point_id, bk.sort_order knowledge_point_sort_order,
                       q.id question_id, q.source_id, q.source_type, q.source_name,
                       q.exam_year, q.question_number
                  FROM question_bank_knowledge bk
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

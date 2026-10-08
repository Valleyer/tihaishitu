package cn.tihaishitu.learning;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.learner.StudyProfileService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.tihaishitu.learning.KnowledgeModelPolicy.READY_THRESHOLD;

@Service
public class LearnerProgressService {
    /** 「最近接触」列表的展示上限，沿用原「最近学习」的 10 条。 */
    private static final int RECENT_CONTACT_LIMIT = 10;

    private final LearnerProgressStore progress;
    private final LearnerActivityStatsService activity;
    private final LearnerKnowledgeStateService states;
    private final ReviewQueueService reviews;
    private final LearnerPracticeStore practices;
    private final StudyProfileService profiles;
    private final KnowledgeQuestionPoolService questionPool;
    private final KnowledgeMasteryModel mastery = new KnowledgeMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public LearnerProgressService(LearnerProgressStore progress, LearnerActivityStatsService activity,
                                  LearnerKnowledgeStateService states,
                                  ReviewQueueService reviews, LearnerPracticeStore practices,
                                  StudyProfileService profiles, KnowledgeQuestionPoolService questionPool) {
        this.progress = progress;
        this.activity = activity;
        this.states = states;
        this.reviews = reviews;
        this.practices = practices;
        this.profiles = profiles;
        this.questionPool = questionPool;
    }

    public ProgressView current() {
        return progressAt(LearnerContext.learnerId(), clock.instant());
    }

    /**
     * 当前 Selected Books 范围内可学习的去重 KnowledgePoint ID 集合。
     *
     * <p>范围事实由 {@link LearnerActivityStatsService} 统一提供，进度聚合与活动统计必须
     * 共用同一份集合，避免出现两套范围口径。</p>
     */
    Set<String> scopedKnowledgePointIds(String learnerId, Instant now) {
        return activity.scopedKnowledgePointIds(learnerId);
    }

    ProgressView progressAt(String learnerId, Instant now) {
        List<LearnerProgressStore.BookRow> books = progress.selectedBooks(learnerId);
        List<LearnerProgressStore.ChapterRow> chapters = progress.selectedChapters(learnerId);
        List<LearnerProgressStore.MembershipRow> memberships = progress.selectedMemberships(learnerId);

        // 有效 Attempt 事实（含仅查看答案）只读一次：统一 activity、旧 recent 兼容投影与最近接触
        // 都从同一份快照派生，避免同一请求重复扫描全历史，也避免读到不同时间点。
        LearnerActivityStatsService.Views derived = activity.views(learnerId, now);

        Map<String, LearnerProgressStore.MembershipRow> pointById = new LinkedHashMap<>();
        memberships.forEach(row -> pointById.putIfAbsent(row.knowledgePointId(), row));
        Map<String, KnowledgeMasteryModel.State> stateByPoint =
                states.settledStates(learnerId, pointById.keySet(), now);

        Map<String, PointProgress> points = new LinkedHashMap<>();
        pointById.forEach((id, row) -> {
            KnowledgeMasteryModel.State state = stateByPoint.getOrDefault(id, KnowledgeMasteryModel.State.initial());
            double effective = mastery.effectiveMastery(state, now);
            points.put(id, new PointProgress(id, row.name(), row.subject(), row.section(), row.chapter(),
                    row.bookName(), row.chapterName(),
                    mastery.band(state, now), effective, state.stabilityDays(), state.evidenceCount(),
                    state.lastEvidenceAt()));
        });

        ReviewQueueService.ReviewQueue reviewQueue = reviews.queueAt(learnerId, now);
        Set<String> dueOrSoon = new LinkedHashSet<>();
        List<ReviewQueueService.ReviewItem> scopedReviewItems = reviewQueue.items().stream()
                .filter(item -> points.containsKey(item.knowledgePointId())).toList();
        scopedReviewItems.stream().filter(item -> !"upcoming".equals(item.status()))
                .forEach(item -> dueOrSoon.add(item.knowledgePointId()));
        int reviewDue = (int) scopedReviewItems.stream().filter(item -> "due".equals(item.status())).count();
        int reviewSoon = (int) scopedReviewItems.stream().filter(item -> "soon".equals(item.status())).count();
        int reviewUpcoming = (int) scopedReviewItems.stream().filter(item -> "upcoming".equals(item.status())).count();

        Counts overall = counts(points.keySet(), points);
        Set<String> wrongScope = questionPool.allowedKnowledgePointIds(new LinkedHashSet<>(
                profiles.rawCurrent(learnerId).selectedBookIds()));
        Summary summary = new Summary(books.size(), overall.total(), overall.started(), overall.ready(),
                overall.proficient(), reviewDue, reviewSoon, reviewUpcoming,
                practices.wrongQuestions(learnerId, wrongScope).size());

        Map<String, Integer> bands = new LinkedHashMap<>();
        for (String band : List.of("unstarted", "unmastered", "learning", "ready", "proficient")) {
            bands.put(band, (int) points.values().stream().filter(point -> band.equals(point.band())).count());
        }

        Map<String, List<LearnerProgressStore.MembershipRow>> membershipsByBook = new LinkedHashMap<>();
        Map<String, List<LearnerProgressStore.ChapterRow>> chaptersByBook = new LinkedHashMap<>();
        memberships.forEach(row -> membershipsByBook.computeIfAbsent(row.bookId(), ignored -> new ArrayList<>()).add(row));
        chapters.forEach(row -> chaptersByBook.computeIfAbsent(row.bookId(), ignored -> new ArrayList<>()).add(row));
        List<BookProgress> bookViews = books.stream().map(book -> bookProgress(book,
                chaptersByBook.getOrDefault(book.id(), List.of()),
                membershipsByBook.getOrDefault(book.id(), List.of()), points, dueOrSoon)).toList();

        // 旧 recent.knowledgePoints：Mastery Evidence 投影，保持 PR7 之前的语义 —— 当前范围内
        // 有 Evidence 且 lastEvidenceAt 非空，按 lastEvidenceAt DESC + knowledgePointId 稳定排序，
        // 至多 10 条。仅为投影，不新建 SQL、不读新表。
        List<GradedRecentPoint> evidencePoints = points.values().stream()
                .filter(point -> point.evidenceCount() > 0 && point.lastEvidenceAt() != null)
                .sorted(Comparator.comparing(PointProgress::lastEvidenceAt).reversed()
                        .thenComparing(PointProgress::knowledgePointId))
                .limit(LearnerActivityStatsService.RECENT_EVIDENCE_LIMIT)
                .map(point -> new GradedRecentPoint(point.knowledgePointId(), point.name(),
                        point.bookName(), point.chapterName(), point.band(), point.effectiveMastery(),
                        point.stabilityDays(), point.lastEvidenceAt()))
                .toList();

        // 最近接触知识点：来源是有效 Attempt（含仅查看答案），而不是 Mastery Evidence；
        // 行为标签由最近一次 Attempt 的真实状态决定（lastOutcomeRevealedOnly / lastGraded），
        // 绝不用 evidenceCount 反推用户做过什么。
        List<RecentContact> recentContacts = derived.contacts().stream()
                .map(contact -> {
                    PointProgress point = points.get(contact.knowledgePointId());
                    return new RecentContact(contact.knowledgePointId(),
                            point == null ? null : point.name(),
                            point == null ? null : point.bookName(),
                            point == null ? null : point.chapterName(),
                            point == null ? "unstarted" : point.band(),
                            point == null ? 0 : point.effectiveMastery(),
                            point == null ? 0 : point.stabilityDays(),
                            point == null ? 0 : point.evidenceCount(),
                            point == null ? null : point.lastEvidenceAt(),
                            contact.lastEffectiveContactAt(), contact.lastOutcomeRevealedOnly(),
                            contact.lastGraded(), contact.assessment());
                })
                .limit(RECENT_CONTACT_LIMIT)
                .toList();
        return new ProgressView(now, summary, bands, bookViews,
                new GradedRecentView(derived.recent().gradedAttempts7d(), derived.recent().distinctKnowledgePoints7d(),
                        derived.recent().activeStudyDays7d(), derived.recent().daily(), evidencePoints),
                recentContacts, derived.activity());
    }

    private BookProgress bookProgress(LearnerProgressStore.BookRow book,
                                      List<LearnerProgressStore.ChapterRow> chapters,
                                      List<LearnerProgressStore.MembershipRow> memberships,
                                      Map<String, PointProgress> points, Set<String> dueOrSoon) {
        Set<String> bookPointIds = new LinkedHashSet<>();
        Map<String, Set<String>> directByChapter = new HashMap<>();
        for (LearnerProgressStore.MembershipRow row : memberships) {
            bookPointIds.add(row.knowledgePointId());
            directByChapter.computeIfAbsent(row.chapterId(), ignored -> new LinkedHashSet<>())
                    .add(row.knowledgePointId());
        }
        Counts counts = counts(bookPointIds, points);
        int reviewDueOrSoon = (int) bookPointIds.stream().filter(dueOrSoon::contains).count();

        List<ChapterProgress> chapterViews = chapters.stream().map(chapter -> {
            Counts chapterCounts = counts(directByChapter.getOrDefault(chapter.id(), Set.of()), points);
            return new ChapterProgress(chapter.id(), chapter.code(), chapter.name(), chapterCounts.total(),
                    chapterCounts.started(), chapterCounts.ready(), chapterCounts.proficient(),
                    chapterCounts.masteryProgress());
        }).toList();
        return new BookProgress(book.id(), book.name(), book.description(), counts.total(), counts.started(),
                counts.ready(), counts.proficient(), counts.masteryProgress(), reviewDueOrSoon, chapterViews);
    }

    private static Counts counts(Set<String> ids, Map<String, PointProgress> points) {
        int started = 0, ready = 0, proficient = 0;
        double masteryTotal = 0;
        for (String id : ids) {
            PointProgress point = points.get(id);
            if (point == null) continue;
            if (point.evidenceCount() > 0) started++;
            if (point.effectiveMastery() >= READY_THRESHOLD) ready++;
            if ("proficient".equals(point.band())) proficient++;
            masteryTotal += point.effectiveMastery();
        }
        return new Counts(ids.size(), started, ready, proficient,
                ids.isEmpty() ? 0 : masteryTotal / ids.size());
    }

    private record PointProgress(String knowledgePointId, String name, String subject, String section,
                                 String chapter, String bookName, String chapterName, String band,
                                 double effectiveMastery, double stabilityDays,
                                 int evidenceCount, Instant lastEvidenceAt) {}
    private record Counts(int total, int started, int ready, int proficient, double masteryProgress) {}
    /**
     * 进度总览。
     *
     * <p>{@code recent} 是保留的旧兼容契约：graded-only 数值（日期取评分日）加上 Evidence 投影
     * 列表 {@code knowledgePoints}；{@code recentContacts} 是 PR7 的最近接触列表（来源为有效
     * Attempt，含仅查看答案）；{@code activity} 是完整有效答题口径。三者语义不同但都由同一次
     * 只读派生得出。</p>
     */
    public record ProgressView(Instant generatedAt, Summary summary, Map<String, Integer> bands,
                               List<BookProgress> books, GradedRecentView recent,
                               List<RecentContact> recentContacts, LearnerActivityStatsService.ActivityView activity) {}
    public record Summary(int selectedBooks, int totalKnowledgePoints, int startedKnowledgePoints,
                          int readyKnowledgePoints, int proficientKnowledgePoints, int reviewDue,
                          int reviewSoon, int reviewUpcoming, int wrongQuestions) {}
    public record BookProgress(String bookId, String name, String description, int totalKnowledgePoints,
                               int started, int ready, int proficient, double masteryProgress, int reviewDueOrSoon,
                               List<ChapterProgress> chapters) {}
    public record ChapterProgress(String chapterId, String code, String name, int total, int started,
                                  int ready, int proficient, double masteryProgress) {}

    /**
     * 旧 {@code recent} 契约。
     *
     * <p>{@code gradedAttempts7d / distinctKnowledgePoints7d / activeStudyDays7d / daily} 是
     * graded-only 且按 {@code answered_at}（评分日）归属上海业务日；{@code knowledgePoints} 恢复
     * 为 Mastery Evidence 投影列表，按 {@code lastEvidenceAt DESC, knowledgePointId} 稳定排序，
     * 至多 10 条、限当前学习范围。仅查看答案的知识点不出现在该列表里。</p>
     */
    public record GradedRecentView(int gradedAttempts7d, int distinctKnowledgePoints7d, int activeStudyDays7d,
                                  List<LearnerActivityStatsService.DailyProgress> daily,
                                  List<GradedRecentPoint> knowledgePoints) {}

    /**
     * 旧 {@code recent.knowledgePoints} 的一条记录（Mastery Evidence 投影）。
     *
     * <p>只暴露正式目录 Book → Chapter 路径，不携带 legacy subject / section /
     * legacy chapter_name，避免用户界面回落到旧字段。</p>
     */
    public record GradedRecentPoint(String knowledgePointId, String name, String bookName, String chapterName,
                                    String band, double effectiveMastery, double stabilityDays,
                                    Instant lastEvidenceAt) {}

    /**
     * 一个「最近接触」的知识点。
     *
     * <p>排序键是 {@code lastEffectiveContactAt}（reveal 或 graded 的首次有效行动），
     * 与 {@code lastEvidenceAt}（Mastery 最后证据时间，仅 graded 会产生）是两个不同概念。</p>
     *
     * <p>行为标签必须看 {@code lastOutcomeRevealedOnly} / {@code lastGraded}：已真实评分但暂无
     * Evidence（{@code lastGraded && evidenceCount == 0}）应说明「已作答 · 暂无掌握证据」，不得
     * 因为 {@code evidenceCount == 0} 就写成「仅查看答案」。</p>
     *
     * <p>只暴露正式目录 Book → Chapter 路径，不携带 legacy subject / section /
     * legacy chapter_name，避免用户界面回落到旧字段。</p>
     */
    public record RecentContact(String knowledgePointId, String name, String bookName, String chapterName,
                                String band, double effectiveMastery, double stabilityDays, int evidenceCount,
                                Instant lastEvidenceAt, Instant lastEffectiveContactAt,
                                boolean lastOutcomeRevealedOnly, boolean lastGraded, String assessment) {}
}

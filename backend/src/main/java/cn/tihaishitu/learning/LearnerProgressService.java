package cn.tihaishitu.learning;

import cn.tihaishitu.game.KnowledgeQuestionPoolService;
import cn.tihaishitu.learner.LearnerContext;
import cn.tihaishitu.learner.StudyProfileService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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

        // 有效 Attempt 事实（含仅查看答案）只读一次，统一 activity 与兼容 recent 从同一份派生。
        LearnerActivityStatsService.Derived derived =
                activity.derive(learnerId, now, LearnerActivityStatsService.WINDOW_DAYS);

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

        // 最近接触知识点：来源是有效 Attempt（含仅查看答案），而不是 Mastery Evidence。
        // 展示沿用既有 Mastery 状态与正式目录路径；仅 reveal、暂无 Evidence 时只显示
        // 「尚未评分」，不编造掌握度。
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
                            contact.lastEffectiveContactAt(), contact.revealedOnly());
                })
                .limit(RECENT_CONTACT_LIMIT)
                .toList();
        return new ProgressView(now, summary, bands, bookViews, derived.recent(), recentContacts);
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
     * <p>{@code recent} 是保留的旧兼容契约（graded-only，字段名与语义不变）；
     * {@code recentContacts} 是 PR7 新增的最近接触列表，来源是有效 Attempt（含仅查看答案），
     * 两者都不改变判题、Mastery V3 与错题本。</p>
     */
    public record ProgressView(Instant generatedAt, Summary summary, Map<String, Integer> bands,
                               List<BookProgress> books, LearnerActivityStatsService.RecentProgress recent,
                               List<RecentContact> recentContacts) {}
    public record Summary(int selectedBooks, int totalKnowledgePoints, int startedKnowledgePoints,
                          int readyKnowledgePoints, int proficientKnowledgePoints, int reviewDue,
                          int reviewSoon, int reviewUpcoming, int wrongQuestions) {}
    public record BookProgress(String bookId, String name, String description, int totalKnowledgePoints,
                               int started, int ready, int proficient, double masteryProgress, int reviewDueOrSoon,
                               List<ChapterProgress> chapters) {}
    public record ChapterProgress(String chapterId, String code, String name, int total, int started,
                                  int ready, int proficient, double masteryProgress) {}
    /**
     * 一个「最近接触」的知识点。
     *
     * <p>排序键是 {@code lastEffectiveContactAt}（reveal 或 graded 的首次有效行动），
     * 与 {@code lastEvidenceAt}（Mastery 最后证据时间，仅 graded 会产生）是两个不同概念。
     * {@code evidenceCount == 0} 表示该知识点只有「仅查看答案」，UI 不得展示掌握度。</p>
     *
     * <p>只暴露正式目录 Book → Chapter 路径，不携带 legacy subject / section /
     * legacy chapter_name，避免用户界面回落到旧字段。</p>
     */
    public record RecentContact(String knowledgePointId, String name, String bookName, String chapterName,
                                String band, double effectiveMastery, double stabilityDays, int evidenceCount,
                                Instant lastEvidenceAt, Instant lastEffectiveContactAt, boolean revealedOnly) {}
}

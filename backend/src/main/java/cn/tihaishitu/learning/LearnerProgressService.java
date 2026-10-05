package cn.tihaishitu.learning;

import cn.tihaishitu.learner.LearnerContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private final LearnerProgressStore progress;
    private final LearnerKnowledgeStateStore states;
    private final ReviewQueueService reviews;
    private final LearnerPracticeStore practices;
    private final KnowledgeMasteryModel mastery = new KnowledgeMasteryModel();
    private final Clock clock = Clock.systemUTC();

    public LearnerProgressService(LearnerProgressStore progress, LearnerKnowledgeStateStore states,
                                  ReviewQueueService reviews, LearnerPracticeStore practices) {
        this.progress = progress;
        this.states = states;
        this.reviews = reviews;
        this.practices = practices;
    }

    public ProgressView current() {
        return progressAt(LearnerContext.learnerId(), clock.instant());
    }

    ProgressView progressAt(String learnerId, Instant now) {
        List<LearnerProgressStore.BookRow> books = progress.selectedBooks(learnerId);
        List<LearnerProgressStore.ChapterRow> chapters = progress.selectedChapters(learnerId);
        List<LearnerProgressStore.MembershipRow> memberships = progress.selectedMemberships(learnerId);

        Map<String, LearnerProgressStore.MembershipRow> pointById = new LinkedHashMap<>();
        memberships.forEach(row -> pointById.putIfAbsent(row.knowledgePointId(), row));
        Map<String, KnowledgeMasteryModel.State> stateByPoint = new HashMap<>();
        states.findForKnowledgePoints(learnerId, pointById.keySet())
                .forEach(row -> stateByPoint.put(row.knowledgePointId(), row.state()));

        Map<String, PointProgress> points = new LinkedHashMap<>();
        pointById.forEach((id, row) -> {
            KnowledgeMasteryModel.State state = stateByPoint.getOrDefault(id, KnowledgeMasteryModel.State.initial());
            double effective = mastery.effectiveMastery(state, now);
            points.put(id, new PointProgress(id, row.name(), row.subject(), row.section(), row.chapter(),
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
        Summary summary = new Summary(books.size(), overall.total(), overall.started(), overall.ready(),
                overall.proficient(), reviewDue, reviewSoon, reviewUpcoming,
                practices.wrongQuestions(learnerId).size());

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

        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant from = today.minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant();
        LearnerProgressStore.RecentTotals recentTotals = progress.recentTotals(learnerId, from, now);
        Map<LocalDate, LearnerProgressStore.DailyRow> dailyRows = new HashMap<>();
        progress.recentDaily(learnerId, from, now).forEach(row -> dailyRows.put(row.date(), row));
        List<DailyProgress> daily = new ArrayList<>();
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            LearnerProgressStore.DailyRow row = dailyRows.get(date);
            daily.add(new DailyProgress(date, row == null ? 0 : row.gradedAttempts(),
                    row == null ? 0 : row.distinctKnowledgePoints()));
        }
        List<RecentKnowledgePoint> recentPoints = points.values().stream()
                .filter(point -> point.evidenceCount() > 0 && point.lastEvidenceAt() != null)
                .sorted(Comparator.comparing(PointProgress::lastEvidenceAt).reversed()
                        .thenComparing(PointProgress::knowledgePointId))
                .limit(10)
                .map(point -> new RecentKnowledgePoint(point.knowledgePointId(), point.name(), point.subject(),
                        point.section(), point.chapter(), point.band(), point.effectiveMastery(),
                        point.stabilityDays(), point.lastEvidenceAt()))
                .toList();
        int activeDays = (int) daily.stream().filter(day -> day.gradedAttempts() > 0).count();
        RecentProgress recent = new RecentProgress(recentTotals.gradedAttempts(),
                recentTotals.distinctKnowledgePoints(), activeDays, List.copyOf(daily), recentPoints);
        return new ProgressView(now, summary, bands, bookViews, recent);
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

        Map<String, ChapterNode> nodes = new LinkedHashMap<>();
        chapters.forEach(row -> nodes.put(row.id(), new ChapterNode(row, directByChapter.getOrDefault(row.id(), Set.of()))));
        List<ChapterNode> roots = new ArrayList<>();
        nodes.values().forEach(node -> {
            ChapterNode parent = nodes.get(node.row.parentId());
            if (parent == null) roots.add(node); else parent.children.add(node);
        });
        List<ChapterProgress> chapterViews = roots.stream()
                .map(root -> chapterProgress(root, points, new LinkedHashSet<>()).view()).toList();
        return new BookProgress(book.id(), book.name(), book.description(), counts.total(), counts.started(),
                counts.ready(), counts.proficient(), reviewDueOrSoon, chapterViews);
    }

    private ChapterAggregate chapterProgress(ChapterNode node, Map<String, PointProgress> points, Set<String> path) {
        if (!path.add(node.row.id())) throw new IllegalStateException("文集章节树存在循环。");
        List<ChapterAggregate> children = node.children.stream()
                .map(child -> chapterProgress(child, points, new LinkedHashSet<>(path))).toList();
        Set<String> subtree = new LinkedHashSet<>(node.directPointIds);
        children.forEach(child -> subtree.addAll(child.knowledgePointIds()));
        Counts counts = counts(subtree, points);
        return new ChapterAggregate(new ChapterProgress(node.row.id(), node.row.code(), node.row.name(),
                counts.total(), counts.started(), counts.ready(), counts.proficient(),
                children.stream().map(ChapterAggregate::view).toList()), Set.copyOf(subtree));
    }

    private static Counts counts(Set<String> ids, Map<String, PointProgress> points) {
        int started = 0, ready = 0, proficient = 0;
        for (String id : ids) {
            PointProgress point = points.get(id);
            if (point == null) continue;
            if (point.evidenceCount() > 0) started++;
            if (point.effectiveMastery() >= READY_THRESHOLD) ready++;
            if ("proficient".equals(point.band())) proficient++;
        }
        return new Counts(ids.size(), started, ready, proficient);
    }

    private record PointProgress(String knowledgePointId, String name, String subject, String section,
                                 String chapter, String band, double effectiveMastery, double stabilityDays,
                                 int evidenceCount, Instant lastEvidenceAt) {}
    private record Counts(int total, int started, int ready, int proficient) {}
    private record ChapterAggregate(ChapterProgress view, Set<String> knowledgePointIds) {}
    private static final class ChapterNode {
        private final LearnerProgressStore.ChapterRow row;
        private final Set<String> directPointIds;
        private final List<ChapterNode> children = new ArrayList<>();
        private ChapterNode(LearnerProgressStore.ChapterRow row, Set<String> directPointIds) {
            this.row = row; this.directPointIds = directPointIds;
        }
    }

    public record ProgressView(Instant generatedAt, Summary summary, Map<String, Integer> bands,
                               List<BookProgress> books, RecentProgress recent) {}
    public record Summary(int selectedBooks, int totalKnowledgePoints, int startedKnowledgePoints,
                          int readyKnowledgePoints, int proficientKnowledgePoints, int reviewDue,
                          int reviewSoon, int reviewUpcoming, int wrongQuestions) {}
    public record BookProgress(String bookId, String name, String description, int totalKnowledgePoints,
                               int started, int ready, int proficient, int reviewDueOrSoon,
                               List<ChapterProgress> chapters) {}
    public record ChapterProgress(String chapterId, String code, String name, int total, int started,
                                  int ready, int proficient, List<ChapterProgress> children) {}
    public record RecentProgress(int gradedAttempts7d, int distinctKnowledgePoints7d, int activeStudyDays7d,
                                 List<DailyProgress> daily, List<RecentKnowledgePoint> knowledgePoints) {}
    public record DailyProgress(LocalDate date, int gradedAttempts, int distinctKnowledgePoints) {}
    public record RecentKnowledgePoint(String knowledgePointId, String name, String subject, String section,
                                       String chapter, String band, double effectiveMastery,
                                       double stabilityDays, Instant lastEvidenceAt) {}
}

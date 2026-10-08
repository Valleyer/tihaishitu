// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { platformApi, type BookDetail, type HubBootstrap, type LearnerProgress, type RecentChapter } from "./api";
import {
  BOOK_TTL_MS,
  PROGRESS_TTL_MS,
  currentCacheGeneration,
  invalidateLearnerAllData,
  invalidateLearnerDynamicData,
  learnerScopeKey,
  loadBook,
  loadProgress,
  loadRecentChapter,
  loadStudyCore,
  normalizeProgressDays,
  peekBook,
  peekProgress,
  peekRecentChapter,
  peekStudyCore,
  prefetchProgress,
  resetLearnerDataCache,
  scheduleHubPrefetch,
} from "./learnerDataCache";

const bookDetail = (id: string, name: string): BookDetail => ({
  id, name, description: "", revision: 1, knowledgePointCount: 10, totalKnowledgePointCount: 10,
  questionCount: 20, chapters: [],
});

const progressFor = (days: 7 | 30 | 90): LearnerProgress => {
  const value: LearnerProgress = {
    generatedAt: "2026-10-09T12:00:00Z",
  summary: { selectedBooks: 1, totalKnowledgePoints: 10, startedKnowledgePoints: 1,
    readyKnowledgePoints: 0, proficientKnowledgePoints: 0, reviewDue: 0, reviewSoon: 0,
    reviewUpcoming: 0, wrongQuestions: 0 },
  bands: { unstarted: 10, unmastered: 0, learning: 1, ready: 0, proficient: 0 },
  books: [],
  recent: { gradedAttempts7d: 0, distinctKnowledgePoints7d: 0, activeStudyDays7d: 0, daily: [], knowledgePoints: [] },
  recentContacts: [],
  activity: { windowDays: days, generatedAt: "2026-10-09T12:00:00Z",
    metrics: { activeStudyDays7d: 0, todayEffectiveAttempts: 0, totalKnowledgePoints: 10,
      touchedKnowledgePoints: 0, totalEffectiveAttempts: 0, totalCorrectAttempts: 0 },
    outcomes: { correct: 0, partial: 0, wrong: 0, revealedOnly: 0 },
    daily: Array.from({ length: days }, (_, index) => ({
      date: `2026-09-${index + 1}`, effectiveAttempts: 0, distinctKnowledgePoints: 0,
      knowledgePoints: 0, correct: 0, partial: 0, wrong: 0, revealedOnly: 0 })) },
  };
  return value;
};

const recentChapter: RecentChapter = { status: "last", lastSessionId: "s", bookId: "math",
  bookName: "考研数学一", chapterId: "c1", chapterName: "第一章", currentKnowledgePointIndex: 0,
  knowledgePointCount: 10, updatedAt: "2026-10-09T12:00:00Z" };

const data = (overrides: Partial<HubBootstrap> = {}): HubBootstrap => ({
  learner: { id: "learner-1", username: "u", displayName: "学习者", revision: 1 },
  canManage: false,
  studyProfile: { pace: "normal", difficulty: "standard", focusMode: "auto", revision: 1,
    selectedBookIds: ["math"], weights: {}, focusedKnowledgePointIds: [], focusedKnowledgePoints: [] },
  worlds: [], questionCatalog: { source: "test", canEdit: false },
  bankManifest: [{ id: "math", name: "考研数学一", description: "", revision: 1,
    knowledgePointCount: 10, totalKnowledgePointCount: 10, questionCount: 20 }],
  ...overrides,
} as HubBootstrap);

let progressSpy: ReturnType<typeof vi.spyOn>;
let bookSpy: ReturnType<typeof vi.spyOn>;
let recentSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  resetLearnerDataCache();
  progressSpy = vi.spyOn(platformApi, "progress").mockImplementation(async days => progressFor(normalizeProgressDays(days)));
  bookSpy = vi.spyOn(platformApi, "book").mockImplementation(async id => bookDetail(id, `文集 ${id}`));
  recentSpy = vi.spyOn(platformApi, "recentChapter").mockResolvedValue(recentChapter);
});

afterEach(() => { vi.restoreAllMocks(); resetLearnerDataCache(); });

describe("learnerDataCache scope key", () => {
  it("separates learner, profile revision and selected books", () => {
    const base = data();
    expect(learnerScopeKey(base)).toBe("learner-1:1:math");
    // selectedBookIds 顺序不影响 key。
    expect(learnerScopeKey(data({ studyProfile: { ...base.studyProfile, selectedBookIds: ["b", "a"] } })))
      .toBe(learnerScopeKey(data({ studyProfile: { ...base.studyProfile, selectedBookIds: ["a", "b"] } })));
    expect(learnerScopeKey(data({ studyProfile: { ...base.studyProfile, revision: 2 } }))).not.toBe(learnerScopeKey(base));
    expect(learnerScopeKey(data({ learner: { ...base.learner, id: "other" } }))).not.toBe(learnerScopeKey(base));
  });
});

describe("learnerDataCache progress keys", () => {
  it("treats progress() and progress(7) as the same 7-day key", async () => {
    await Promise.all([loadProgress(data()), loadProgress(data(), 7)]);
    expect(progressSpy).toHaveBeenCalledTimes(1);
    expect(progressSpy).toHaveBeenCalledWith(7);
    expect(normalizeProgressDays(undefined)).toBe(7);
    // 第二次读取直接命中缓存，不再请求。
    await loadProgress(data(), 7);
    expect(progressSpy).toHaveBeenCalledTimes(1);
  });

  it("keeps 7 / 30 / 90 in independent cache slots", async () => {
    const [seven, thirty, ninety] = await Promise.all([
      loadProgress(data(), 7), loadProgress(data(), 30), loadProgress(data(), 90)]);
    expect(progressSpy).toHaveBeenCalledTimes(3);
    expect([seven.activity.windowDays, thirty.activity.windowDays, ninety.activity.windowDays]).toEqual([7, 30, 90]);
    expect(peekProgress(data(), 30)!.activity.windowDays).toBe(30);
    // 各窗口独立：读 7 不会命中 30。
    await loadProgress(data(), 7);
    expect(progressSpy).toHaveBeenCalledTimes(3);
  });

  it("dedupes concurrent in-flight requests for one window", async () => {
    await Promise.all([loadProgress(data(), 30), loadProgress(data(), 30), loadProgress(data(), 30)]);
    expect(progressSpy).toHaveBeenCalledTimes(1);
  });

  it("reuses one progress:7 request across the home page and Study Core", async () => {
    const [progress, core] = await Promise.all([loadProgress(data(), 7), loadStudyCore(data())]);
    expect(progressSpy).toHaveBeenCalledTimes(1);
    expect(core.progress!.activity.windowDays).toBe(7);
    expect(core.details).toHaveLength(1);
    expect(core.recent).toEqual(recentChapter);
    // Study Core 还复用了 book / recent 缓存。
    expect(bookSpy).toHaveBeenCalledTimes(1);
    expect(recentSpy).toHaveBeenCalledTimes(1);
    expect(peekProgress(data(), 7)).toBe(progress);
  });

  it("does not hit the old cache after the profile revision or selected books change", async () => {
    await loadProgress(data(), 7);
    expect(progressSpy).toHaveBeenCalledTimes(1);

    await loadProgress(data({ studyProfile: { ...data().studyProfile, revision: 2 } }), 7);
    expect(progressSpy).toHaveBeenCalledTimes(2);

    await loadProgress(data({ studyProfile: { ...data().studyProfile, selectedBookIds: ["math", "cs"] } }), 7);
    expect(progressSpy).toHaveBeenCalledTimes(3);

    // 旧 scope 的缓存仍然独立可用，不与新 scope 混用。
    expect(peekProgress(data(), 7)).toBeDefined();
    expect(peekProgress(data({ studyProfile: { ...data().studyProfile, revision: 2 } }), 7)).toBeDefined();
  });
});

describe("learnerDataCache book and recent", () => {
  it("caches book detail per book id and recent chapter per scope", async () => {
    await Promise.all([loadBook(data(), "math"), loadBook(data(), "math"), loadBook(data(), "cs")]);
    expect(bookSpy).toHaveBeenCalledTimes(2);
    expect(peekBook(data(), "math")!.name).toBe("文集 math");

    await Promise.all([loadRecentChapter(data()), loadRecentChapter(data())]);
    expect(recentSpy).toHaveBeenCalledTimes(1);
    expect(peekRecentChapter(data())).toEqual(recentChapter);
  });

  it("revalidates after the TTL without dropping the stale value", async () => {
    await loadProgress(data(), 7);
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + PROGRESS_TTL_MS + 1);
    // 过期后仍能 peek 到 stale 值（UI 不应被清空）。
    expect(peekProgress(data(), 7)).toBeDefined();
    await loadProgress(data(), 7);
    expect(progressSpy).toHaveBeenCalledTimes(2);
  });

  it("honours the longer book TTL", async () => {
    await loadBook(data(), "math");
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + PROGRESS_TTL_MS + 1);
    await loadBook(data(), "math");
    // 超过 progress TTL 但未到 book TTL，仍然命中。
    expect(bookSpy).toHaveBeenCalledTimes(1);
    vi.spyOn(Date, "now").mockReturnValue(Date.now() + BOOK_TTL_MS + 1);
    await loadBook(data(), "math");
    expect(bookSpy).toHaveBeenCalledTimes(2);
  });
});

describe("learnerDataCache invalidation", () => {
  it("drops progress, recent and Study Core on dynamic invalidate but keeps books", async () => {
    await loadStudyCore(data());
    expect(peekStudyCore(data())).toBeDefined();

    invalidateLearnerDynamicData();
    expect(peekProgress(data(), 7)).toBeUndefined();
    expect(peekProgress(data(), 30)).toBeUndefined();
    expect(peekProgress(data(), 90)).toBeUndefined();
    expect(peekRecentChapter(data())).toBeUndefined();
    expect(peekStudyCore(data())).toBeUndefined();
    // 文集详情不属于动态数据，仍然命中。
    expect(peekBook(data(), "math")).toBeDefined();
    expect(bookSpy).toHaveBeenCalledTimes(1);

    await loadProgress(data(), 7);
    expect(progressSpy).toHaveBeenCalledTimes(2);
  });

  it("drops books as well on all invalidate", async () => {
    await loadStudyCore(data());
    invalidateLearnerAllData();
    expect(peekBook(data(), "math")).toBeUndefined();
    await loadBook(data(), "math");
    expect(bookSpy).toHaveBeenCalledTimes(2);
  });

  it("does not let an old in-flight response write back after invalidation", async () => {
    let release!: (value: LearnerProgress) => void;
    progressSpy.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));

    const pending = loadProgress(data(), 7);
    expect(progressSpy).toHaveBeenCalledTimes(1);
    const generationBefore = currentCacheGeneration();

    // 请求还在飞的时候发生 invalidate（例如用户做了一道题）。
    invalidateLearnerDynamicData();
    expect(currentCacheGeneration()).toBeGreaterThan(generationBefore);

    release(progressFor(7));
    const value = await pending;
    // 调用方仍拿到值，但缓存不得被过期数据复活。
    expect(value.activity.windowDays).toBe(7);
    expect(peekProgress(data(), 7)).toBeUndefined();

    await loadProgress(data(), 7);
    expect(progressSpy).toHaveBeenCalledTimes(2);
  });

  it("does not let an old in-flight Study Core snapshot write back after invalidation", async () => {
    let release!: (value: LearnerProgress) => void;
    progressSpy.mockImplementationOnce(() => new Promise(resolve => { release = resolve; }));

    const pending = loadStudyCore(data());
    invalidateLearnerDynamicData();
    release(progressFor(7));
    await pending;
    expect(peekStudyCore(data())).toBeUndefined();
  });
});

describe("learnerDataCache prefetch", () => {
  it("warms progress 7 and Study Core once on the bootstrap idle task", async () => {
    const idle = vi.fn((task: () => void) => { task(); return 1; });
    (window as unknown as { requestIdleCallback: typeof idle }).requestIdleCallback = idle;

    scheduleHubPrefetch(data());
    expect(idle).toHaveBeenCalledTimes(1);
    // 预取是异步的：等微任务清空后，progress 7 只请求一次（Study Core 与之 dedupe）。
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(progressSpy).toHaveBeenCalledTimes(1);
    expect(progressSpy).toHaveBeenCalledWith(7);
    expect(peekProgress(data(), 7)).toBeDefined();
    // 不预热 30 / 90。
    expect(peekProgress(data(), 30)).toBeUndefined();
    expect(peekProgress(data(), 90)).toBeUndefined();
  });

  it("does not re-request when hovering the same prefetch repeatedly", async () => {
    prefetchProgress(data(), 7);
    prefetchProgress(data(), 7);
    prefetchProgress(data(), 7);
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(progressSpy).toHaveBeenCalledTimes(1);
  });
});

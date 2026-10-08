// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { platformApi, type BookDetail, type HubBootstrap, type LearnerProgress, type RecentChapter } from "./api";
import { StudyPage } from "./PlatformApp";

const chapters = [
  { id: "c1", code: "c1", name: "函数与极限", description: "", sortOrder: 0, knowledgePointCount: 52,
    trainableKnowledgePointCount: 52, publishedQuestionCount: 52, availableKnowledgePointCount: 40, knowledgePoints: [] },
  { id: "c2", code: "c2", name: "导数与微分", description: "", sortOrder: 1, knowledgePointCount: 38,
    trainableKnowledgePointCount: 38, publishedQuestionCount: 38, availableKnowledgePointCount: 30, knowledgePoints: [] },
  { id: "c3", code: "c3", name: "微分中值定理与导数的应用", description: "", sortOrder: 2, knowledgePointCount: 41,
    trainableKnowledgePointCount: 41, publishedQuestionCount: 41, availableKnowledgePointCount: 41, knowledgePoints: [] },
  { id: "c4", code: "c4", name: "不定积分", description: "", sortOrder: 3, knowledgePointCount: 29,
    trainableKnowledgePointCount: 29, publishedQuestionCount: 29, availableKnowledgePointCount: 29, knowledgePoints: [] },
];

const math: BookDetail = { id: "math1", name: "考研数学一", description: "", revision: 1,
  knowledgePointCount: 340, totalKnowledgePointCount: 469, questionCount: 900, chapters };

const data = {
  learner: { id: "learner", username: "learner", displayName: "学习者", revision: 1 },
  canManage: false,
  studyProfile: { pace: "normal", difficulty: "standard", focusMode: "auto", revision: 1,
    selectedBookIds: ["math1", "cs408"], weights: {}, focusedKnowledgePointIds: [], focusedKnowledgePoints: [] },
  worlds: [], questionCatalog: { source: "test", canEdit: false },
  bankManifest: [
    { id: "math1", name: "考研数学一", description: "", revision: 1, knowledgePointCount: 340,
      totalKnowledgePointCount: 469, questionCount: 900 },
    { id: "cs408", name: "考研408", description: "", revision: 1, knowledgePointCount: 399,
      totalKnowledgePointCount: 1018, questionCount: 700 },
  ],
} as HubBootstrap;

const progress = {
  generatedAt: "2026-10-05T12:00:00Z",
  summary: { selectedBooks: 2, totalKnowledgePoints: 739, startedKnowledgePoints: 9,
    readyKnowledgePoints: 4, proficientKnowledgePoints: 1, reviewDue: 1, reviewSoon: 1,
    reviewUpcoming: 1, wrongQuestions: 7 },
  bands: { unstarted: 730, unmastered: 4, learning: 4, ready: 1, proficient: 1 },
  books: [{ bookId: "math1", name: "考研数学一", description: "", totalKnowledgePoints: 340, started: 9,
    ready: 4, proficient: 1, masteryProgress: 12, reviewDueOrSoon: 2,
    chapters: chapters.map(item => ({ chapterId: item.id, code: item.code, name: item.name,
      total: item.knowledgePointCount, started: 0, ready: 0, proficient: 0,
      masteryProgress: item.id === "c3" ? 35 : 12 })) }],
  recent: { gradedAttempts7d: 0, distinctKnowledgePoints7d: 0, activeStudyDays7d: 0, daily: [], knowledgePoints: [] },
  recentContacts: [],
  activity: { windowDays: 7, generatedAt: "2026-10-05T12:00:00Z",
    metrics: { activeStudyDays7d: 5, todayEffectiveAttempts: 3, totalKnowledgePoints: 739,
      touchedKnowledgePoints: 9, totalEffectiveAttempts: 12, totalCorrectAttempts: 9 },
    outcomes: { correct: 9, partial: 1, wrong: 2, revealedOnly: 0 }, daily: [] },
} as LearnerProgress;

const recentChapter: RecentChapter = { status: "last", lastSessionId: "s1", bookId: "math1",
  bookName: "考研数学一", chapterId: "c3", chapterName: "微分中值定理与导数的应用",
  currentKnowledgePointIndex: 2, knowledgePointCount: 52, updatedAt: "2026-10-08T21:10:00Z" };

function mockStudy(overrides: { recent?: RecentChapter } = {}) {
  vi.spyOn(platformApi, "book").mockImplementation(async id => id === "cs408"
    ? { ...math, id: "cs408", name: "考研408", chapters: [] } as BookDetail
    : math);
  vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([]);
  vi.spyOn(platformApi, "progress").mockResolvedValue(progress);
  vi.spyOn(platformApi, "recentChapter").mockResolvedValue(overrides.recent ?? recentChapter);
  const startChapter = vi.spyOn(platformApi, "startChapterPractice")
    .mockResolvedValue({ id: "session", intent: "chapter_drill", status: "active", revision: 1,
      flowComplete: false, canRepeat: false, currentAttempt: undefined } as never);
  Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
  return { startChapter };
}

const renderStudy = async (overrides: { recent?: RecentChapter } = {}) => {
  const spies = mockStudy(overrides);
  const view = render(<StudyPage data={data} reload={vi.fn()} />);
  await screen.findByText("学习状态");
  return { ...spies, view };
};

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Study page layout", () => {
  it("shows the current book and chapter above a progress bar with one inline percentage", async () => {
    const { view } = await renderStudy();

    const hero = view.container.querySelector(".study-hero")!;
    expect(hero.querySelector(".study-hero-book")!.textContent).toBe("考研数学一");
    expect(hero.querySelector(".study-hero-chapter")!.textContent).toBe("微分中值定理与导数的应用");
    const bar = view.container.querySelector(".study-progress")!;
    expect(bar.getAttribute("aria-label")).toBe("章节掌握进度 35%");
    expect(bar.querySelector("span")!.getAttribute("style")).toContain("width: 35%");
    // 百分比只出现一次，且不再有「当前进度：2 / 52」这类分式文字。
    expect(screen.getAllByText("35%")).toHaveLength(1);
    expect(screen.queryByText(/2 \/ 52/)).toBeNull();
    expect(screen.queryByText(/当前进度/)).toBeNull();
  });

  it("keeps the practice button free of a text arrow", async () => {
    await renderStudy();
    const button = screen.getByRole("button", { name: "再次练习" });
    expect(button.textContent).toBe("再次练习");
  });

  it("removes the recent-chapter label and its timestamp", async () => {
    const { view } = await renderStudy();
    expect(screen.queryByText("最近练习章节")).toBeNull();
    expect(screen.queryByText(/最近练习 /)).toBeNull();
    expect(view.container.querySelector(".continue-time")).toBeNull();
    expect(view.container.querySelector(".section-kicker")).toBeNull();
  });

  it("shows exactly four study status items and never the active-study-day metric", async () => {
    const { view } = await renderStudy();

    const items = view.container.querySelectorAll(".today-metrics > p");
    expect(items).toHaveLength(4);
    expect([...items].map(item => item.textContent)).toEqual([
      "已开始知识点9", "熟练掌握及以上4", "错题本题目7", "今日答题3",
    ]);
    expect(screen.queryByText("近 7 天活跃学习日")).toBeNull();
    expect(view.container.textContent).not.toContain("活跃学习日");
  });

  it("renders books as cards and chapters as three-column numbered cards with a plain bar", async () => {
    const { view } = await renderStudy();

    expect(view.container.querySelectorAll(".book-cards .book-cover-card")).toHaveLength(2);
    const cards = view.container.querySelectorAll(".chapter-cards .chapter-card");
    expect(cards).toHaveLength(4);
    // 每张章节卡：装饰字形 + 章节名 + 细进度条 + chevron，且不显示百分比文字。
    expect(cards[0].querySelector(".chapter-number")!.textContent).toBe("∑");
    expect(cards[0].querySelector("b")!.textContent).toBe("函数与极限");
    expect(cards[0].querySelector(".chapter-bar")).toBeTruthy();
    expect(cards[0].querySelector(".chapter-chevron")).toBeTruthy();
    expect(cards[0].textContent).not.toContain("%");
    expect(view.container.querySelector(".chapter-body")).toBeNull();
  });

  it("keeps the book click scoped to this page and does not touch the study range", async () => {
    const update = vi.spyOn(platformApi, "updateProfile");
    const { view } = await renderStudy();

    fireEvent.click(view.container.querySelectorAll(".book-cover-card")[1]);
    // 顶部卡片切换到所选书籍，且没有写学习范围。
    await waitFor(() => expect(view.container.querySelector(".study-hero-book")!.textContent).toBe("考研408"));
    expect(update).not.toHaveBeenCalled();
  });

  it("starts a chapter practice with the existing behaviour", async () => {
    const { startChapter } = await renderStudy();

    fireEvent.click(screen.getByRole("button", { name: /微分中值定理与导数的应用/ }));
    fireEvent.click(await screen.findByRole("button", { name: "开始章节练习" }));
    await waitFor(() => expect(startChapter).toHaveBeenCalledWith("math1", "c3"));
  });
});

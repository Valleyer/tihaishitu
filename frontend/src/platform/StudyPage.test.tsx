// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { platformApi, type BookDetail, type HubBootstrap, type LearnerProgress, type RecentChapter } from "./api";
import { StudyPage } from "./PlatformApp";

/** 11 个章节：用于验证「每页最多 9 个」的纯前端本地分页。 */
const manyChapters = Array.from({ length: 11 }, (_, index) => ({
  id: `m${index + 1}`, code: `m${index + 1}`, name: `第 ${index + 1} 章`, description: "", sortOrder: index,
  knowledgePointCount: 10 + index, trainableKnowledgePointCount: 10 + index, publishedQuestionCount: 10 + index,
  availableKnowledgePointCount: 10 + index, knowledgePoints: [],
}));

/** 2 个章节：用于验证 ≤9 时不出现翻页箭头。 */
const csChapters = [
  { id: "d1", code: "d1", name: "数据结构", description: "", sortOrder: 0, knowledgePointCount: 90,
    trainableKnowledgePointCount: 90, publishedQuestionCount: 90, availableKnowledgePointCount: 80, knowledgePoints: [] },
  { id: "d2", code: "d2", name: "操作系统", description: "", sortOrder: 1, knowledgePointCount: 70,
    trainableKnowledgePointCount: 70, publishedQuestionCount: 70, availableKnowledgePointCount: 70, knowledgePoints: [] },
];

const math: BookDetail = { id: "math1", name: "考研数学一", description: "", revision: 1,
  knowledgePointCount: 340, totalKnowledgePointCount: 469, questionCount: 900, chapters: manyChapters };

const cs: BookDetail = { id: "cs408", name: "考研408", description: "", revision: 1,
  knowledgePointCount: 399, totalKnowledgePointCount: 1018, questionCount: 700, chapters: csChapters };

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
    chapters: manyChapters.map(item => ({ chapterId: item.id, code: item.code, name: item.name,
      total: item.knowledgePointCount, started: 0, ready: 0, proficient: 0,
      masteryProgress: item.id === "m3" ? 35 : 12 })) }],
  recent: { gradedAttempts7d: 0, distinctKnowledgePoints7d: 0, activeStudyDays7d: 0, daily: [], knowledgePoints: [] },
  recentContacts: [],
  activity: { windowDays: 7, generatedAt: "2026-10-05T12:00:00Z",
    metrics: { activeStudyDays7d: 5, todayEffectiveAttempts: 3, totalKnowledgePoints: 739,
      touchedKnowledgePoints: 9, totalEffectiveAttempts: 12, totalCorrectAttempts: 9 },
    outcomes: { correct: 9, partial: 1, wrong: 2, revealedOnly: 0 }, daily: [] },
} as LearnerProgress;

/** 最近一次章节练习落在数学一的「第 3 章」，进度 35%。 */
const recentChapter: RecentChapter = { status: "last", lastSessionId: "s1", bookId: "math1",
  bookName: "考研数学一", chapterId: "m3", chapterName: "第 3 章",
  currentKnowledgePointIndex: 2, knowledgePointCount: 52, updatedAt: "2026-10-08T21:10:00Z" };

function mockStudy(overrides: { recent?: RecentChapter } = {}) {
  const book = vi.spyOn(platformApi, "book").mockImplementation(async id => id === "cs408" ? cs : math);
  vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([]);
  vi.spyOn(platformApi, "progress").mockResolvedValue(progress);
  vi.spyOn(platformApi, "recentChapter").mockResolvedValue(overrides.recent ?? recentChapter);
  const startChapter = vi.spyOn(platformApi, "startChapterPractice").mockResolvedValue(
    { id: "session", intent: "chapter_drill", status: "active", revision: 1 } as never);
  const updateProfile = vi.spyOn(platformApi, "updateProfile");
  Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
  return { book, startChapter, updateProfile };
}

const renderStudy = async (overrides: { recent?: RecentChapter } = {}) => {
  const spies = mockStudy(overrides);
  const view = render(<StudyPage data={data} reload={vi.fn()} />);
  await screen.findByText("学习状态");
  return { ...spies, view };
};

const chapterCards = (view: { container: HTMLElement }) => view.container.querySelectorAll(".chapter-card");
const bookCards = (view: { container: HTMLElement }) => view.container.querySelectorAll(".book-cover-card");
const chapterNames = (view: { container: HTMLElement }) =>
  [...chapterCards(view)].map(card => card.querySelector("b")!.textContent);

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Study page default state", () => {
  it("starts with no book selected and no chapters rendered", async () => {
    const { view } = await renderStudy();

    expect(bookCards(view)).toHaveLength(2);
    expect(view.container.querySelectorAll(".book-cover-card.selected")).toHaveLength(0);
    expect(chapterCards(view)).toHaveLength(0);
    expect(view.container.querySelector(".chapter-cards")).toBeNull();
    expect(screen.queryByRole("button", { name: "开始章节练习" })).toBeNull();
    // 未选书时不渲染翻页容器，也不显示页码或空态提示。
    expect(view.container.querySelector(".chapter-browser")).toBeNull();
    expect(screen.queryByRole("button", { name: "上一页章节" })).toBeNull();
    expect(screen.queryByRole("button", { name: "下一页章节" })).toBeNull();
    expect(screen.queryByText(/请选择/)).toBeNull();
    expect(screen.queryByText(/选择章节/)).toBeNull();
  });

  it("shows the recent study card from the recent chapter only", async () => {
    const { view } = await renderStudy();

    const hero = view.container.querySelector(".study-hero")!;
    expect(hero.querySelector(".study-hero-book")!.textContent).toBe("考研数学一");
    expect(hero.querySelector(".study-hero-chapter")!.textContent).toBe("第 3 章");
    expect(hero.querySelector(".study-progress")!.getAttribute("aria-label")).toBe("章节掌握进度 35%");
    expect(hero.querySelector(".study-progress span")!.getAttribute("style")).toContain("width: 35%");
    // 百分比只出现一次，且没有分式进度、没有标签与时间戳。
    expect(screen.getAllByText("35%")).toHaveLength(1);
    expect(hero.textContent).not.toContain("最近练习章节");
    expect(hero.textContent).not.toContain("2 / 52");
  });

  it("keeps the practice button free of any arrow", async () => {
    await renderStudy();
    expect(screen.getByRole("button", { name: "再次练习" }).textContent).toBe("再次练习");
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
});

describe("Study page chapter browsing", () => {
  it("renders chapters only after the user picks a book", async () => {
    const { view } = await renderStudy();

    fireEvent.click(bookCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    expect(view.container.querySelectorAll(".book-cover-card.selected")).toHaveLength(1);
    expect(chapterNames(view)).toEqual(["数据结构", "操作系统"]);
    // 未选章节时不出现章节操作区。
    expect(screen.queryByRole("button", { name: "开始章节练习" })).toBeNull();
  });

  it("shows no paging arrows when a book has at most nine chapters", async () => {
    const { view } = await renderStudy();

    fireEvent.click(bookCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    expect(view.container.querySelector(".chapter-browser")).toBeNull();
    expect(screen.queryByRole("button", { name: "上一页章节" })).toBeNull();
    expect(screen.queryByRole("button", { name: "下一页章节" })).toBeNull();
  });

  it("switches to the other book's chapters and clears the selected chapter", async () => {
    const { view } = await renderStudy();

    fireEvent.click(bookCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    fireEvent.click(chapterCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)[1].className).toContain("selected"));
    expect(await screen.findByRole("button", { name: "开始章节练习" })).toBeTruthy();

    fireEvent.click(bookCards(view)[0]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(9));
    // 换书后章节选择被清空，操作区随之消失。
    expect(view.container.querySelectorAll(".chapter-card.selected")).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "开始章节练习" })).toBeNull();
  });

  it("never lets a book click change the recent study card or the study range", async () => {
    const { view, updateProfile } = await renderStudy();
    const hero = () => view.container.querySelector(".study-hero")!;

    fireEvent.click(bookCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));

    expect(hero().querySelector(".study-hero-book")!.textContent).toBe("考研数学一");
    expect(hero().querySelector(".study-hero-chapter")!.textContent).toBe("第 3 章");
    expect(hero().querySelector(".study-progress span")!.getAttribute("style")).toContain("width: 35%");
    // 书籍点击只切换本页浏览，不写学习范围。
    expect(updateProfile).not.toHaveBeenCalled();
  });

  it("keeps chapter clicking scoped to this page", async () => {
    const { view, updateProfile } = await renderStudy();

    fireEvent.click(bookCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    fireEvent.click(chapterCards(view)[0]);
    await waitFor(() => expect(chapterCards(view)[0].className).toContain("selected"));

    expect(view.container.querySelector(".study-hero-chapter")!.textContent).toBe("第 3 章");
    expect(updateProfile).not.toHaveBeenCalled();
  });

  it("starts a chapter practice with the browsed book and chapter", async () => {
    const { view, startChapter } = await renderStudy();

    fireEvent.click(bookCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    fireEvent.click(chapterCards(view)[1]);
    fireEvent.click(await screen.findByRole("button", { name: "开始章节练习" }));
    await waitFor(() => expect(startChapter).toHaveBeenCalledWith("cs408", "d2"));
  });
});

describe("Study page local chapter paging", () => {
  it("renders at most nine chapters per page and pages locally without a new request", async () => {
    const { view, book } = await renderStudy();

    fireEvent.click(bookCards(view)[0]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(9));
    expect(chapterNames(view)).toEqual(Array.from({ length: 9 }, (_, index) => `第 ${index + 1} 章`));

    const previous = screen.getByRole("button", { name: "上一页章节" }) as HTMLButtonElement;
    const next = screen.getByRole("button", { name: "下一页章节" }) as HTMLButtonElement;
    expect(previous.disabled).toBe(true);
    expect(next.disabled).toBe(false);
    // 不显示页码或分页说明文字。
    expect(view.container.querySelector(".chapter-browser")!.textContent).not.toMatch(/\d\s*\/\s*\d/);
    const chapterRequests = book.mock.calls.length;

    // 下一页：本地 slice 出剩余 2 个章节，且不重新请求后端章节。
    fireEvent.click(next);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    expect(chapterNames(view)).toEqual(["第 10 章", "第 11 章"]);
    expect((screen.getByRole("button", { name: "下一页章节" }) as HTMLButtonElement).disabled).toBe(true);
    expect(book.mock.calls.length).toBe(chapterRequests);

    // 上一页：回到第一页 9 项。
    fireEvent.click(screen.getByRole("button", { name: "上一页章节" }));
    await waitFor(() => expect(chapterCards(view)).toHaveLength(9));
    expect((screen.getByRole("button", { name: "上一页章节" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("clears the selected chapter when paging", async () => {
    const { view } = await renderStudy();

    fireEvent.click(bookCards(view)[0]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(9));
    fireEvent.click(chapterCards(view)[0]);
    expect(await screen.findByRole("button", { name: "开始章节练习" })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "下一页章节" }));
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    expect(view.container.querySelectorAll(".chapter-card.selected")).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "开始章节练习" })).toBeNull();
  });

  it("resets to the first page and clears the chapter when switching books", async () => {
    const { view } = await renderStudy();

    fireEvent.click(bookCards(view)[0]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(9));
    fireEvent.click(screen.getByRole("button", { name: "下一页章节" }));
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    fireEvent.click(chapterCards(view)[0]);
    expect(await screen.findByRole("button", { name: "开始章节练习" })).toBeTruthy();

    fireEvent.click(bookCards(view)[1]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(2));
    expect(chapterNames(view)).toEqual(["数据结构", "操作系统"]);
    expect(screen.queryByRole("button", { name: "开始章节练习" })).toBeNull();
    expect(screen.queryByRole("button", { name: "上一页章节" })).toBeNull();
  });
});

describe("Study page chapter cards and art", () => {
  it("uses education icons instead of math glyphs and shows no percentage text", async () => {
    const { view } = await renderStudy();

    fireEvent.click(bookCards(view)[0]);
    await waitFor(() => expect(chapterCards(view)).toHaveLength(9));

    for (const card of chapterCards(view)) {
      // 图标是 SVG，而不是 ∑ / ∫ / π 之类的数学字形文本。
      expect(card.querySelector(".chapter-number svg")).toBeTruthy();
      expect(card.querySelector(".chapter-number")!.textContent).toBe("");
      expect(card.querySelector(".chapter-bar")).toBeTruthy();
      expect(card.querySelector(".chapter-chevron svg")).toBeTruthy();
      // 卡片只保留图标、章节名、细进度条与 chevron，不显示百分比或知识点数。
      expect(card.textContent).not.toContain("%");
      expect(card.textContent).not.toContain("个知识点");
    }
    expect(view.container.textContent).not.toMatch(/[∑∫π∞]/);
  });

  it("renders a text-free book illustration in the recent card", async () => {
    const { view } = await renderStudy();

    const art = view.container.querySelector(".study-hero-art")!;
    expect(art.getAttribute("aria-hidden")).toBe("true");
    // 插画里不得出现任何文字或公式（含「数学一」与 π / 积分号等）。
    expect(art.querySelectorAll("text")).toHaveLength(0);
    expect(art.textContent).toBe("");
  });
});

describe("Shell header", () => {
  it("marks the current module active and shows an avatar in the account pill", async () => {
    history.replaceState(null, "", "/study");
    const { view } = await renderStudy();

    const nav = view.container.querySelector("nav[aria-label='主要导航']")!;
    const active = nav.querySelectorAll("a.active");
    expect(active).toHaveLength(1);
    expect(active[0].textContent).toBe("学习");
    // 只改选中视觉，不改路由判断。
    expect(active[0].getAttribute("href")).toBe("/study");

    // 账号入口是带浅紫头像的胶囊，文案仍是用户名，点击仍进账号页。
    const account = view.container.querySelector(".hub-account")!;
    expect(account.querySelector(".hub-account-avatar svg")).toBeTruthy();
    expect(account.querySelector(".hub-account-avatar")!.getAttribute("aria-hidden")).toBe("true");
    expect(account.textContent).toContain("学习者");
    expect(account.getAttribute("href")).toBe("/account");
  });
});

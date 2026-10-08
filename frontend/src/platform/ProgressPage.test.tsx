// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { platformApi, type HubBootstrap, type LearnerActivity, type LearnerProgress } from "./api";
import { AuthenticatedPlatform, ProgressPage } from "./PlatformApp";
import { progressMetrics } from "./ProgressPanels";
import { isHubPath } from "./navigation";

const bootstrap = {
  learner: { id: "learner", username: "learner", displayName: "学习者", revision: 1 },
  canManage: false,
  studyProfile: { pace: "normal", difficulty: "standard", focusMode: "auto", revision: 1,
    selectedBookIds: ["math"], weights: {}, focusedKnowledgePointIds: [], focusedKnowledgePoints: [] },
  worlds: [], questionCatalog: { source: "test", canEdit: false },
  bankManifest: [{ id: "math", name: "考研数学一", description: "", revision: 1,
    knowledgePointCount: 267, totalKnowledgePointCount: 469, questionCount: 100 }],
} as HubBootstrap;

const activity = (overrides: Partial<LearnerActivity> = {}): LearnerActivity => ({
  windowDays: 7, generatedAt: "2026-10-05T12:00:00Z",
  metrics: {
    activeStudyDays7d: 3, todayEffectiveAttempts: 5, totalKnowledgePoints: 267,
    touchedKnowledgePoints: 42, totalEffectiveAttempts: 128, totalCorrectAttempts: 90,
  },
  outcomes: { correct: 90, partial: 12, wrong: 20, revealedOnly: 6 },
  daily: [
    { date: "2026-09-29", effectiveAttempts: 0, distinctKnowledgePoints: 0, correct: 0, partial: 0, wrong: 0, revealedOnly: 0 },
    { date: "2026-09-30", effectiveAttempts: 2, distinctKnowledgePoints: 2, correct: 2, partial: 0, wrong: 0, revealedOnly: 0 },
    { date: "2026-10-01", effectiveAttempts: 0, distinctKnowledgePoints: 0, correct: 0, partial: 0, wrong: 0, revealedOnly: 0 },
    { date: "2026-10-02", effectiveAttempts: 4, distinctKnowledgePoints: 3, correct: 3, partial: 0, wrong: 1, revealedOnly: 0 },
    { date: "2026-10-03", effectiveAttempts: 0, distinctKnowledgePoints: 0, correct: 0, partial: 0, wrong: 0, revealedOnly: 0 },
    { date: "2026-10-04", effectiveAttempts: 1, distinctKnowledgePoints: 1, correct: 0, partial: 0, wrong: 0, revealedOnly: 1 },
    { date: "2026-10-05", effectiveAttempts: 5, distinctKnowledgePoints: 4, correct: 4, partial: 0, wrong: 1, revealedOnly: 0 },
  ],
  ...overrides,
});

const progress = (overrides: Partial<LearnerProgress> = {}): LearnerProgress => ({
  generatedAt: "2026-10-05T12:00:00Z",
  summary: { selectedBooks: 1, totalKnowledgePoints: 267, startedKnowledgePoints: 42,
    readyKnowledgePoints: 10, proficientKnowledgePoints: 3, reviewDue: 2, reviewSoon: 1,
    reviewUpcoming: 4, wrongQuestions: 7 },
  bands: { unstarted: 225, unmastered: 10, learning: 19, ready: 10, proficient: 3 },
  books: [{ bookId: "math", name: "考研数学一", description: "", totalKnowledgePoints: 267,
    started: 42, ready: 10, proficient: 3, masteryProgress: 37.4, reviewDueOrSoon: 3,
    chapters: [{ chapterId: "chapter-1", code: "A", name: "第一章", total: 20, started: 5,
      ready: 2, proficient: 1, masteryProgress: 22.5 }] }],
  activity: activity(),
  recent: { knowledgePoints: [{ knowledgePointId: "point-1", name: "函数极限", bookName: "考研数学一",
    chapterName: "第一章", band: "learning", effectiveMastery: 62.5, stabilityDays: 4,
    lastEvidenceAt: "2026-10-05T02:00:00Z" }] },
  ...overrides,
});

function mockPlatform(data: LearnerProgress = progress()) {
  const load = vi.spyOn(platformApi, "progress").mockResolvedValue(data);
  vi.spyOn(platformApi, "bootstrap").mockResolvedValue(bootstrap);
  const statistics = vi.spyOn(platformApi, "statistics");
  return { load, statistics };
}

beforeEach(() => {
  Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
  history.replaceState(null, "", "/progress");
});

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("progress metrics contract", () => {
  it("maps the six unified metrics to their product labels in order", () => {
    const metrics = progressMetrics(activity().metrics);
    expect(metrics.map(metric => metric.label)).toEqual(
      ["活跃学习日", "今日答题", "当前范围知识点", "接触知识点", "累计答题", "累计正确"]);
    expect(metrics.map(metric => metric.value)).toEqual([3, 5, 267, 42, 128, 90]);
  });
});

describe("ProgressPage", () => {
  it("renders the six unified metrics from the single progress response", async () => {
    const { load, statistics } = mockPlatform();
    render(<ProgressPage data={bootstrap} />);

    expect(await screen.findByText("活跃学习日")).toBeTruthy();
    for (const label of ["今日答题", "当前范围知识点", "接触知识点", "累计答题", "累计正确"]) {
      expect(screen.getByText(label)).toBeTruthy();
    }
    // 进度与统计共用一份事实：进度页只请求一次 /learner/progress，不再请求第二套统计。
    expect(load).toHaveBeenCalledTimes(1);
    expect(statistics).not.toHaveBeenCalled();
  });

  it("renders seven daily bars including zero-value days and never a 30/90 day switch", async () => {
    mockPlatform();
    const view = render(<ProgressPage data={bootstrap} />);
    await screen.findByText("近 7 天趋势");

    const columns = view.container.querySelectorAll(".chart-column");
    expect(columns).toHaveLength(7);
    // 零值日期不隐藏：0 活动的 3 天仍然占列。
    expect(view.container.querySelectorAll(".chart-bar.attempts")).toHaveLength(7);
    expect(view.container.querySelectorAll(".chart-bar.points")).toHaveLength(7);
    expect(view.container.querySelector(".chart-column")!.getAttribute("title"))
      .toContain("有效答题 0 次");
    // 7/30/90 天切换控件已移除。
    expect(screen.queryByRole("button", { name: /近 (30|90) 天/ })).toBeNull();
    expect(screen.queryByText("学习统计")).toBeNull();
  });

  it("shows a real empty state without fabricating records", async () => {
    const empty = activity({
      metrics: { activeStudyDays7d: 0, todayEffectiveAttempts: 0, totalKnowledgePoints: 0,
        touchedKnowledgePoints: 0, totalEffectiveAttempts: 0, totalCorrectAttempts: 0 },
      outcomes: { correct: 0, partial: 0, wrong: 0, revealedOnly: 0 },
      daily: activity().daily.map(day => ({ ...day, effectiveAttempts: 0, distinctKnowledgePoints: 0,
        correct: 0, partial: 0, wrong: 0, revealedOnly: 0 })),
    });
    mockPlatform(progress({ activity: empty, books: [], recent: { knowledgePoints: [] } }));
    render(<ProgressPage data={bootstrap} />);

    expect(await screen.findByText(/这 7 天还没有有效答题记录/)).toBeTruthy();
    expect(screen.getByText("还没有学习记录")).toBeTruthy();
    expect(screen.getByText("尚未选择学习文集")).toBeTruthy();
  });

  it("separates revealed-only from wrong in the outcome distribution", async () => {
    mockPlatform();
    const view = render(<ProgressPage data={bootstrap} />);
    await screen.findByText("答题结果分布");

    // 「仅查看答案」是独立一类，不并入错误，也不计入掌握。
    expect(screen.getByText("仅查看答案")).toBeTruthy();
    expect(screen.getByText("错误")).toBeTruthy();
    const rows = view.container.querySelectorAll(".outcome-rows .horizontal-stat");
    expect(rows).toHaveLength(4);
    expect(rows[3].textContent).toContain("仅查看答案");
    expect(rows[3].querySelector("b")!.className).toBe("revealed");
    // 四类之和与累计答题一致。
    expect(screen.getByText("累计 128 次有效答题")).toBeTruthy();
  });

  it("keeps book and chapter progress entry points reachable", async () => {
    mockPlatform();
    render(<ProgressPage data={bootstrap} />);
    await screen.findByText("考研数学一");

    expect(screen.getByRole("link", { name: "查看章节进度 →" }).getAttribute("href"))
      .toBe("/progress/books/math");
    expect(screen.getByRole("link", { name: /函数极限/ }).getAttribute("href"))
      .toBe("/knowledge/point-1");
  });

  it("stays readable when the progress request fails", async () => {
    vi.spyOn(platformApi, "bootstrap").mockResolvedValue(bootstrap);
    vi.spyOn(platformApi, "progress").mockRejectedValue(new Error("网络不可用"));
    render(<ProgressPage data={bootstrap} />);

    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByText("网络不可用")).toBeTruthy();
  });
});

describe("unified progress navigation", () => {
  it("drops the statistics entry from the header and keeps only progress", async () => {
    mockPlatform();
    render(<AuthenticatedPlatform />);

    const nav = await screen.findByRole("navigation", { name: "主要导航" });
    const labels = [...nav.querySelectorAll("a")].map(link => link.textContent);
    expect(labels).toContain("进度");
    expect(labels).not.toContain("统计");
  });

  it("redirects /statistics to /progress with a single progress request", async () => {
    const { load } = mockPlatform();
    history.replaceState(null, "", "/statistics");
    render(<AuthenticatedPlatform />);

    expect(await screen.findByText("今日答题")).toBeTruthy();
    // URL 被替换（而不是 push），返回链不会再次落到 /statistics。
    expect(location.pathname).toBe("/progress");
    expect(isHubPath("/statistics")).toBe(true);
    expect(load).toHaveBeenCalledTimes(1);
  });

  it("serves the progress sub pages directly", async () => {
    mockPlatform();
    history.replaceState(null, "", "/progress/books/math");
    render(<AuthenticatedPlatform />);

    expect(await screen.findByRole("heading", { name: "考研数学一" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "← 返回文集进度" }).getAttribute("href")).toBe("/progress");
  });

  it("waits for the bootstrap response before rendering the scope", async () => {
    mockPlatform();
    render(<AuthenticatedPlatform />);
    await waitFor(() => expect(screen.getByText("考研数学一")).toBeTruthy());
  });
});

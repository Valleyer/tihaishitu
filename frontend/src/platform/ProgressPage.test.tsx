// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { platformApi, type ActivityDaily, type HubBootstrap, type LearnerActivity, type LearnerProgress } from "./api";
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

/** 生成恰好 n 条连续上海业务日曲线，含零值日；值随窗口长度变化以便断言真实切换。 */
function dailySeries(days: number, perDay: (index: number) => { attempts: number; points: number }): ActivityDaily[] {
  const start = Date.UTC(2026, 8, 29);
  return Array.from({ length: days }, (_, index) => {
    const { attempts, points } = perDay(index);
    const date = new Date(start + index * 86_400_000).toISOString().slice(0, 10);
    return { date, effectiveAttempts: attempts, distinctKnowledgePoints: points,
      correct: 0, partial: 0, wrong: 0, revealedOnly: 0 };
  });
}

const metrics = { activeStudyDays7d: 3, todayEffectiveAttempts: 5, totalKnowledgePoints: 267,
  touchedKnowledgePoints: 42, totalEffectiveAttempts: 128, totalCorrectAttempts: 90 };

const outcomes = { correct: 90, partial: 12, wrong: 20, revealedOnly: 6 };

function activityFor(days: 7 | 30 | 90): LearnerActivity {
  // 默认 7 天有 3 个非零日；30/90 天窗口更长但值不同，可用来证明切换真的重新取数。
  const series = days === 7
    ? dailySeries(7, index => index % 2 === 0 ? { attempts: index + 1, points: index } : { attempts: 0, points: 0 })
    : days === 30
      ? dailySeries(30, index => index === 0 ? { attempts: 11, points: 4 } : { attempts: 0, points: 0 })
      : dailySeries(90, index => index === 89 ? { attempts: 22, points: 9 } : { attempts: 0, points: 0 });
  return { windowDays: days, generatedAt: "2026-10-05T12:00:00Z", metrics, outcomes, daily: series };
}

const progress = (days: 7 | 30 | 90 = 7): LearnerProgress => ({
  generatedAt: "2026-10-05T12:00:00Z",
  summary: { selectedBooks: 1, totalKnowledgePoints: 267, startedKnowledgePoints: 42,
    readyKnowledgePoints: 10, proficientKnowledgePoints: 3, reviewDue: 2, reviewSoon: 1,
    reviewUpcoming: 4, wrongQuestions: 7 },
  bands: { unstarted: 225, unmastered: 10, learning: 19, ready: 10, proficient: 3 },
  books: [{ bookId: "math", name: "考研数学一", description: "", totalKnowledgePoints: 267,
    started: 42, ready: 10, proficient: 3, masteryProgress: 37.4, reviewDueOrSoon: 3,
    chapters: [{ chapterId: "chapter-1", code: "A", name: "第一章", total: 20, started: 5,
      ready: 2, proficient: 1, masteryProgress: 22.5 }] }],
  activity: activityFor(days),
  recent: {
    gradedAttempts7d: 8, distinctKnowledgePoints7d: 6, activeStudyDays7d: 3,
    daily: activityFor(7).daily.map(day => ({ date: day.date,
      gradedAttempts: day.effectiveAttempts, distinctKnowledgePoints: day.distinctKnowledgePoints })),
    knowledgePoints: [],
  },
  recentContacts: [
    { knowledgePointId: "point-1", name: "函数极限", bookName: "考研数学一", chapterName: "第一章",
      band: "learning", effectiveMastery: 62.5, stabilityDays: 4, evidenceCount: 5,
      lastEvidenceAt: "2026-10-05T02:00:00Z", lastEffectiveContactAt: "2026-10-05T02:00:00Z",
      lastOutcomeRevealedOnly: false, lastGraded: true, assessment: "correct" },
  ],
});

/** 每次按 days 返回对应窗口的响应，模拟后端真实行为。 */
function mockPlatform(load?: (days?: 7 | 30 | 90) => LearnerProgress) {
  const progressSpy = vi.spyOn(platformApi, "progress").mockImplementation(async days => load ? load(days) : progress(days ?? 7));
  vi.spyOn(platformApi, "bootstrap").mockResolvedValue(bootstrap);
  const statistics = vi.spyOn(platformApi, "statistics");
  return { progressSpy, statistics };
}

beforeEach(() => {
  Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
  history.replaceState(null, "", "/progress");
});

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("progress metrics contract", () => {
  it("projects only a label and a value for the six unified metrics in order", () => {
    const projected = progressMetrics(metrics);
    expect(projected.map(metric => metric.label)).toEqual(
      ["活跃学习日", "今日答题", "当前范围知识点", "接触知识点", "累计答题", "累计正确"]);
    expect(projected.map(metric => metric.value)).toEqual([3, 5, 267, 42, 128, 90]);
    // 只允许 label / value：不得再出现单位或 hint 字段。
    expect(projected.every(metric => Object.keys(metric).sort().join() === "label,value")).toBe(true);
  });
});

describe("ProgressPage header and time range", () => {
  it("restores the old title block with only the title and the 7/30/90 selector", async () => {
    mockPlatform();
    const view = render(<ProgressPage data={bootstrap} />);

    expect(await screen.findByRole("heading", { name: "学习进度" })).toBeTruthy();
    const heading = view.container.querySelector(".panel-heading")!;
    const buttons = [...heading.querySelectorAll("button")];
    expect(buttons.map(button => button.textContent)).toEqual(["近 7 天", "近 30 天", "近 90 天"]);
    // 标题区只含标题与选择器：没有 eyebrow、范围徽标或介绍文字。
    expect(heading.querySelectorAll("h1")).toHaveLength(1);
    expect(heading.textContent?.replace(/近 \d+ 天/g, "").trim()).toBe("学习进度");
    expect(screen.queryByText("当前学习范围")).toBeNull();
    expect(screen.queryByText(/可学习知识点/)).toBeNull();
  });

  it("defaults to 7 days and requests the default window", async () => {
    const { progressSpy } = mockPlatform();
    render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });

    expect(progressSpy).toHaveBeenCalledWith(7);
    expect(screen.getByRole("button", { name: "近 7 天" }).className).toBe("active");
  });

  it("switches both charts to the real 30 and 90 day windows", async () => {
    const { progressSpy } = mockPlatform();
    const view = render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });
    const columns = () => view.container.querySelectorAll(".statistics-activity-chart .chart-column");
    // 两张图各 7 列。
    expect(columns()).toHaveLength(14);

    fireEvent.click(screen.getByRole("button", { name: "近 30 天" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "近 30 天" }).className).toBe("active"));
    expect(progressSpy).toHaveBeenLastCalledWith(30);
    // 两张图同步切到 30 条，标题角标同步。
    await waitFor(() => expect(columns()).toHaveLength(60));
    expect(screen.getAllByText("近 30 天 · 单位：次")).toHaveLength(1);
    expect(screen.getAllByText("近 30 天 · 单位：个")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "近 90 天" }));
    expect(progressSpy).toHaveBeenLastCalledWith(90);
    await waitFor(() => expect(columns()).toHaveLength(180));
    expect(screen.getAllByText("近 90 天 · 单位：次")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "近 7 天" }));
    expect(progressSpy).toHaveBeenLastCalledWith(7);
    await waitFor(() => expect(columns()).toHaveLength(14));
  });

  it("never falls back to the legacy graded-only statistics endpoint", async () => {
    const { statistics } = mockPlatform();
    render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });
    fireEvent.click(screen.getByRole("button", { name: "近 90 天" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "近 90 天" }).className).toBe("active"));
    expect(statistics).not.toHaveBeenCalled();
  });

  it("keeps the full-history metrics and the fixed seven-day active day metric", async () => {
    mockPlatform();
    render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });

    const values = () => [...document.querySelectorAll(".statistics-summary b")].map(node => node.textContent);
    expect(values()).toEqual(["3", "5", "267", "42", "128", "90"]);

    fireEvent.click(screen.getByRole("button", { name: "近 90 天" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "近 90 天" }).className).toBe("active"));
    // 切换只影响趋势曲线，不改动任何指标（活跃学习日仍为近 7 天定义）。
    await waitFor(() => expect(values()).toEqual(["3", "5", "267", "42", "128", "90"]));
  });
});

describe("ProgressPage layout and content", () => {
  it("renders the six metrics without units or explanations in the DOM", async () => {
    mockPlatform();
    const view = render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });

    const summary = view.container.querySelector(".statistics-summary")!;
    expect(summary.querySelectorAll("article")).toHaveLength(6);
    expect(summary.textContent).not.toMatch(/[天次个]/);
    expect(summary.textContent).not.toContain("近 7 天有答题记录的天数");
    expect(summary.textContent).not.toContain("今天发生的有效答题");
    expect(summary.textContent).not.toContain("全历史");
  });

  it("restores the two legacy charts with the new titles, units and data sources", async () => {
    mockPlatform();
    const view = render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });

    const headings = [...view.container.querySelectorAll(".statistics-activity-chart h2")].map(node => node.textContent);
    expect(headings).toEqual(["每日答题次数", "每日接触知识点"]);
    expect(screen.getByText("近 7 天 · 单位：次")).toBeTruthy();
    expect(screen.getByText("近 7 天 · 单位：个")).toBeTruthy();
    expect(screen.getByText("数量（次）")).toBeTruthy();
    expect(screen.getByText("数量（个）")).toBeTruthy();
    // 单系列旧结构：每个日期一列一根柱，保留零值日。
    const charts = view.container.querySelectorAll(".statistics-activity-chart");
    expect(charts).toHaveLength(2);
    expect(charts[0].querySelectorAll(".chart-column")).toHaveLength(7);
    expect(charts[0].querySelectorAll(".chart-column i")).toHaveLength(7);
    expect(charts[0].querySelector(".chart-column")!.getAttribute("title")).toContain("1 次");
    expect(charts[1].querySelector(".chart-column")!.getAttribute("title")).toContain("0 个");
  });

  it("removes the outcome distribution and the study footprint modules", async () => {
    mockPlatform();
    const view = render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });

    expect(screen.queryByText("答题结果分布")).toBeNull();
    expect(screen.queryByText("学习足迹")).toBeNull();
    // 后端字段仍在，只是本页不再渲染。
    expect(view.container.querySelector(".recent-points")).toBeNull();
  });

  it("keeps the book cards and their deep links without a separate title block", async () => {
    mockPlatform();
    render(<ProgressPage data={bootstrap} />);
    await screen.findByRole("heading", { name: "学习进度" });

    expect(screen.getByText("考研数学一")).toBeTruthy();
    expect(screen.queryByText("文集掌握进度")).toBeNull();
    expect(screen.queryByText(/有效掌握平均值/)).toBeNull();
    expect(screen.getByRole("link", { name: "查看章节进度 →" }).getAttribute("href"))
      .toBe("/progress/books/math");
  });

  it("keeps the empty learning scope state and its entry point", async () => {
    mockPlatform(days => ({ ...progress(days ?? 7), books: [] }));
    render(<ProgressPage data={bootstrap} />);

    expect(await screen.findByText("尚未选择学习文集")).toBeTruthy();
    expect(screen.getByRole("link", { name: "设置学习范围" }).getAttribute("href")).toBe("/study");
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
    const { progressSpy } = mockPlatform();
    history.replaceState(null, "", "/statistics");
    render(<AuthenticatedPlatform />);

    expect(await screen.findByRole("heading", { name: "学习进度" })).toBeTruthy();
    expect(location.pathname).toBe("/progress");
    expect(isHubPath("/statistics")).toBe(true);
    expect(progressSpy).toHaveBeenCalledTimes(1);
  });

  it("serves the progress sub pages directly", async () => {
    mockPlatform();
    history.replaceState(null, "", "/progress/books/math");
    render(<AuthenticatedPlatform />);

    expect(await screen.findByRole("heading", { name: "考研数学一" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "← 返回文集进度" }).getAttribute("href")).toBe("/progress");
  });
});

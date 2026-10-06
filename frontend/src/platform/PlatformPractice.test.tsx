// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { platformApi, type HubBootstrap, type PracticeSession } from "./api";
import { PracticePage, QuestionPreviewCard, safePracticeReturnTo, StudyPage, WrongQuestionsPage } from "./PlatformApp";
import { AnswerDisplay } from "./practiceView";

const data = {
  learner: { id: "learner", username: "learner", displayName: "学习者", revision: 1 },
  canManage: false,
  studyProfile: { pace: "normal", difficulty: "standard", focusMode: "auto", revision: 1,
    selectedBookIds: ["math"], weights: {}, focusedKnowledgePointIds: [], focusedKnowledgePoints: [] },
  worlds: [], questionCatalog: { source: "test", canEdit: false },
  bankManifest: [
    { id: "math", name: "考研数学一", description: "", revision: 1, knowledgePointCount: 267, totalKnowledgePointCount: 469, questionCount: 100 },
    { id: "408", name: "408", description: "", revision: 1, knowledgePointCount: 0, totalKnowledgePointCount: 1018, questionCount: 0 },
  ],
} as HubBootstrap;

const session = (assessment: "correct" | "partial" | "wrong" = "correct", canRepeat = true): PracticeSession => ({
  id: "session", intent: "knowledge_drill", targetKnowledgePointId: "point", status: "active", revision: 1,
  flowComplete: true, canRepeat,
  currentAttempt: {
    id: "attempt", status: "graded", targetKnowledgePointId: "point", targetKnowledgePointName: "函数",
    evidenceMode: "normal", question: { id: "question", subject: "数学一", chapter: "函数",
      presentationType: "single_choice", gradingMode: "auto", question: "题干 $x^2$", options: { A: "甲", B: "乙" }, difficulty: 2 },
    standard: "B", explanation: "解析 $x$", assessment, gradingSource: "automatic", answerRevealed: true,
  },
});

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("practice interaction closure", () => {
  it("selects a saved trainable book by clicking the whole card and keeps draft scope separate", async () => {
    vi.spyOn(platformApi, "book").mockResolvedValue({ ...data.bankManifest[0], chapters: [{ id: "chapter", code: "C",
      name: "函数章", description: "", knowledgePointCount: 1, trainableKnowledgePointCount: 1,
      publishedQuestionCount: 1, knowledgePoints: [{ id: "point", code: "P", name: "函数", subject: "数学",
        section: "函数", chapter: "函数章", description: "", explanation: "" }], sortOrder: 1 }] });
    vi.spyOn(platformApi, "knowledgeStatesForBook").mockResolvedValue([]);
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([]);
    vi.spyOn(platformApi, "progress").mockRejectedValue(new Error("not needed"));
    vi.spyOn(platformApi, "startChapterPractice").mockResolvedValue({ ...session(), intent: "chapter_drill" });
    Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
    render(<StudyPage data={data} reload={vi.fn()} />);

    const mathCard = screen.getByRole("button", { name: /考研数学一.*267 个知识点/ });
    expect(screen.queryByText("选择文集")).toBeNull();
    expect(screen.queryByText(/可学习知识点/)).toBeNull();
    fireEvent.click(mathCard);
    expect(mathCard.classList.contains("selected")).toBe(true);

    expect(screen.queryByRole("button", { name: /408/ })).toBeNull();
    fireEvent.click(screen.getByLabelText(/408/));
    expect(screen.queryByRole("button", { name: /408/ })).toBeNull();
    fireEvent.click(await screen.findByRole("button", { name: "函数章" }));
    fireEvent.click(await screen.findByRole("button", { name: "开始章节练习" }));
    await waitFor(() => expect(`${window.location.pathname}${window.location.search}`)
      .toBe("/practice/session?returnTo=%2Fstudy"));
  });

  it("formats objective standards without JSON quotes", () => {
    const { rerender } = render(<AnswerDisplay standard="B" presentationType="single_choice" options={{ A: "甲", B: "乙" }} />);
    expect(screen.getByText("B")).toBeTruthy();
    expect(screen.queryByText('"B"')).toBeNull();
    rerender(<AnswerDisplay standard={["C", "A"]} presentationType="multiple_choice" options={{ A: "甲", B: "乙", C: "丙" }} />);
    expect(screen.getByText("A、C")).toBeTruthy();
    rerender(<AnswerDisplay standard={true} presentationType="true_false" />);
    expect(screen.getByText("正确")).toBeTruthy();
    rerender(<AnswerDisplay standard={false} presentationType="true_false" />);
    expect(screen.getByText("错误")).toBeTruthy();
  });

  it("shows semantic result feedback and immediate actions before the explanation", async () => {
    vi.spyOn(platformApi, "practice").mockResolvedValue(session("correct"));
    const view = render(<PracticePage data={data} id="session" />);
    const result = await screen.findByText("✓ 回答正确");
    const resultPanel = result.closest(".practice-result")!;
    const answerPanel = view.container.querySelector(".practice-answer")!;
    expect(resultPanel.classList.contains("correct")).toBe(true);
    expect(screen.getByRole("button", { name: "下一道题" })).toBeTruthy();
    expect(resultPanel.compareDocumentPosition(answerPanel) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    cleanup();
    vi.mocked(platformApi.practice).mockResolvedValue(session("wrong"));
    const wrongView = render(<PracticePage data={data} id="session" />);
    expect(await screen.findByText("✕ 回答错误")).toBeTruthy();
    expect(wrongView.container.querySelector(".practice-result.wrong")).toBeTruthy();
  });

  it("disables chapter practice when no knowledge point is currently available", async () => {
    vi.spyOn(platformApi, "book").mockResolvedValue({ ...data.bankManifest[0], chapters: [{ id: "chapter", code: "C",
      name: "多元函数微分学", description: "", knowledgePointCount: 2, trainableKnowledgePointCount: 2,
      availableKnowledgePointCount: 0, publishedQuestionCount: 2, sortOrder: 1,
      knowledgePoints: [{ id: "point", code: "P", name: "多元函数微分学", subject: "数学一",
        section: "高等数学", chapter: "多元函数微分学", description: "", explanation: "" }] }] });
    vi.spyOn(platformApi, "knowledgeStatesForBook").mockResolvedValue([]);
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([]);
    vi.spyOn(platformApi, "progress").mockRejectedValue(new Error("not needed"));
    const startChapter = vi.spyOn(platformApi, "startChapterPractice")
      .mockResolvedValue({ ...session(), intent: "chapter_drill" });
    Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
    render(<StudyPage data={data} reload={vi.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: /考研数学一/ }));
    fireEvent.click(await screen.findByRole("button", { name: "多元函数微分学" }));
    expect(await screen.findByText("本章共 2 个知识点，当前 0 个知识点可练。")).toBeTruthy();
    const button = screen.getByRole("button", { name: "暂无可练正式题" });
    expect(button.hasAttribute("disabled")).toBe(true);
    fireEvent.click(button);
    expect(startChapter).not.toHaveBeenCalled();
  });

  it("renders wrong-question Markdown and LaTeX from the complete source", async () => {
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([{ questionId: "q", targetKnowledgePointId: "k",
      knowledgePointName: "函数", contentMarkdown: "求 $f(x)=x^2$ 的导数", lastGradedAt: "2026-10-06T00:00:00Z", available: true }]);
    const view = render(<WrongQuestionsPage data={data} />);
    await waitFor(() => expect(view.container.querySelector(".wrong-question-content .katex")).toBeTruthy());
    expect(view.container.querySelector(".wrong-question-content")).toBeTruthy();
    expect(screen.queryByText(/数学一.*函数/)).toBeNull();
  });

  it("keeps the wrong-question empty state concise and returns to Study", async () => {
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([]);
    render(<WrongQuestionsPage data={data} />);
    expect(await screen.findByText("错题本还是空的")).toBeTruthy();
    expect(screen.getByRole("link", { name: "← 返回学习" }).getAttribute("href")).toBe("/study");
    expect(screen.queryByText(/这里只保留/)).toBeNull();
    expect(screen.queryByText(/之后若同一道题/)).toBeNull();
    expect(screen.queryByText("错题练习", { selector: ".eyebrow" })).toBeNull();
  });

  it("marks only mastered question previews", () => {
    const summary = { id: "q", subject: "数学", sourceType: "custom", questionType: "single_choice",
      presentationType: "single_choice", gradingMode: "auto", contentMarkdown: "题干", analysisMarkdown: "",
      standardAnswer: "A", difficulty: 2, revision: 1, learnerQuestionStatus: "mastered" as const };
    const view = render(<QuestionPreviewCard summary={summary} />);
    expect(screen.getByText("✓ 已掌握")).toBeTruthy();
    view.rerender(<QuestionPreviewCard summary={{ ...summary, learnerQuestionStatus: "unseen" }} />);
    expect(screen.queryByText("✓ 已掌握")).toBeNull();
  });

  it("uses a safe return path and hides the unavailable next action", async () => {
    window.history.replaceState(null, "", "/practice/session?returnTo=%2Fstudy");
    Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
    vi.spyOn(platformApi, "practice").mockResolvedValue(session("correct", false));
    vi.spyOn(platformApi, "endPractice").mockResolvedValue({ ...session("correct", false), status: "ended" });
    render(<PracticePage data={data} id="session" />);
    expect(await screen.findByText("本轮可练题目已完成")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "下一道题" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "结束专项" }));
    await waitFor(() => expect(window.location.pathname).toBe("/study"));

    window.history.replaceState(null, "", "/practice/session?returnTo=%2Fknowledge%2Fpoint");
    expect(safePracticeReturnTo("knowledge_drill")).toBe("/knowledge/point");
    window.history.replaceState(null, "", "/practice/session?returnTo=https%3A%2F%2Fevil.example");
    expect(safePracticeReturnTo("knowledge_drill")).toBe("/study");
    expect(safePracticeReturnTo("wrong_review")).toBe("/wrong-questions");
  });
});

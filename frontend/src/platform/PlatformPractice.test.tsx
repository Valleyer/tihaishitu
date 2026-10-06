// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { platformApi, type HubBootstrap, type PracticeSession } from "./api";
import { PracticePage, StudyPage, WrongQuestionsPage } from "./PlatformApp";
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

const session = (assessment: "correct" | "partial" | "wrong" = "correct"): PracticeSession => ({
  id: "session", intent: "knowledge_drill", targetKnowledgePointId: "point", status: "active", revision: 1,
  flowComplete: true, canRepeat: true,
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
    vi.spyOn(platformApi, "book").mockResolvedValue({ ...data.bankManifest[0], chapters: [] });
    vi.spyOn(platformApi, "knowledgeStatesForBook").mockResolvedValue([]);
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([]);
    vi.spyOn(platformApi, "progress").mockRejectedValue(new Error("not needed"));
    render(<StudyPage data={data} reload={vi.fn()} />);

    const mathCard = screen.getByRole("button", { name: /考研数学一.*267 个知识点/ });
    expect(screen.queryByText("选择文集")).toBeNull();
    expect(screen.queryByText(/可学习知识点/)).toBeNull();
    fireEvent.click(mathCard);
    expect(mathCard.classList.contains("selected")).toBe(true);

    const book408 = screen.getByRole("button", { name: /408.*0 个知识点.*未加入学习范围/ }) as HTMLButtonElement;
    expect(book408.disabled).toBe(true);
    fireEvent.click(screen.getByLabelText(/408/));
    expect(book408.disabled).toBe(true);
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

  it("renders wrong-question Markdown and LaTeX from the complete source", async () => {
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([{ questionId: "q", targetKnowledgePointId: "k",
      knowledgePointName: "函数", contentMarkdown: "求 $f(x)=x^2$ 的导数", lastGradedAt: "2026-10-06T00:00:00Z" }]);
    const view = render(<WrongQuestionsPage data={data} />);
    await waitFor(() => expect(view.container.querySelector(".wrong-question-content .katex")).toBeTruthy());
    expect(view.container.querySelector(".wrong-question-content")).toBeTruthy();
    expect(screen.queryByText(/数学一.*函数/)).toBeNull();
  });
});

// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { resetLearnerDataCache } from "./learnerDataCache";
import { platformApi, type BookDetail, type BrowseQuestion, type HubBootstrap, type PracticeSession } from "./api";
import { PracticePage, QuestionPage, QuestionPreviewCard, safePracticeReturnTo, StudyPage, WrongQuestionsPage } from "./PlatformApp";
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

const session = (assessment: "correct" | "partial" | "wrong" = "correct", canRepeat = true): PracticeSession => ({  id: "session", intent: "knowledge_drill", targetKnowledgePointId: "point", status: "active", revision: 1,
  flowComplete: true, canRepeat,
  currentAttempt: {
    id: "attempt", status: "graded", targetKnowledgePointId: "point", targetKnowledgePointName: "函数",
    evidenceMode: "normal", question: { id: "question", subject: "数学一", chapter: "函数",
      presentationType: "single_choice", gradingMode: "auto", question: "题干 $x^2$", options: { A: "甲", B: "乙" }, difficulty: 2 },
    standard: "B", explanation: "解析 $x$", assessment, gradingSource: "automatic", answerRevealed: true,
  },
});

afterEach(() => { cleanup(); vi.restoreAllMocks(); resetLearnerDataCache(); });

/** 学习页顶部卡片需要一个真实存在于书籍目录里的最近章节，才能解析出当前章节与可练知识点。 */
const bookWithChapter = (): BookDetail => ({
  ...data.bankManifest[0], chapters: [{
    id: "chapter", code: "CH1", name: "函数章", description: "", sortOrder: 0,
    knowledgePointCount: 2, trainableKnowledgePointCount: 2, publishedQuestionCount: 2,
    availableKnowledgePointCount: 0, knowledgePoints: [],
  }],
});

const browseQuestion = (overrides: Partial<BrowseQuestion> = {}): BrowseQuestion => ({
  id: "question", subject: "数学一", sourceType: "real_exam", sourceName: "2020年考研数学一真题",
  examYear: 2020, questionNumber: "2020-7", displayQuestionNumber: "7", questionType: "single_choice",
  presentationType: "single_choice", gradingMode: "auto", contentMarkdown: "题干 $x^2$",
  analysisMarkdown: "解析", correctAnswer: "C", difficulty: 2, revision: 1,
  options: [{ key: "A", text: "甲" }, { key: "B", text: "乙" }, { key: "C", text: "丙" }],
  // 全平台题目：知识点可能属于 Learner 尚未选择的文集。
  knowledgePoints: [{ id: "point", code: "K", name: "数列极限计算", subject: "数学一", section: "",
    chapter: "", description: "", explanation: "", role: "core" }],
  ...overrides,
});

describe("global question detail", () => {
  it("shows the objective answer without JSON quotes and links back to the question bank", async () => {
    const question = vi.spyOn(platformApi, "question").mockResolvedValue(browseQuestion());
    const view = render(<QuestionPage data={data} id="question" />);

    await waitFor(() => expect(view.container.querySelector(".question-meta")).toBeTruthy());
    expect(view.container.querySelector(".question-meta")!.textContent).toContain("第 7 题");
    expect(screen.getByRole("link", { name: "← 返回题库" }).getAttribute("href")).toBe("/questions");
    // 全局题库场景的知识点标签不做链接。
    expect(view.container.querySelector('a[href="/knowledge/point"]')).toBeNull();
    expect(screen.getByText("数列极限计算").tagName).toBe("SPAN");

    fireEvent.click(screen.getByRole("button", { name: "查看答案与解析" }));
    expect(await screen.findByText("参考答案")).toBeTruthy();
    expect(view.container.querySelector(".practice-answer-value")!.textContent).toBe("C");
    expect(screen.queryByText('"C"')).toBeNull();
    expect(question).toHaveBeenCalledWith("question");
  });

  it("renders multiple choice and true/false answers in Chinese", async () => {
    const question = vi.spyOn(platformApi, "question");
    question.mockResolvedValue(browseQuestion({ presentationType: "multiple_choice", questionType: "multiple_choice",
      correctAnswer: ["C", "A"] }));
    const multiple = render(<QuestionPage data={data} id="question" />);
    fireEvent.click(await screen.findByRole("button", { name: "查看答案与解析" }));
    await waitFor(() => expect(multiple.container.querySelector(".practice-answer-value")).toBeTruthy());
    expect(multiple.container.querySelector(".practice-answer-value")!.textContent).toBe("A、C");
    cleanup();

    question.mockResolvedValue(browseQuestion({ presentationType: "true_false", questionType: "true_false",
      correctAnswer: true, options: [{ key: "true", text: "正确" }, { key: "false", text: "错误" }] }));
    const judge = render(<QuestionPage data={data} id="question" />);
    fireEvent.click(await screen.findByRole("button", { name: "查看答案与解析" }));
    await waitFor(() => expect(judge.container.querySelector(".practice-answer-value")).toBeTruthy());
    expect(judge.container.querySelector(".practice-answer-value")!.textContent).toBe("正确");
  });

  it("shows only the reference analysis for a solution question", async () => {
    vi.spyOn(platformApi, "question").mockResolvedValue(browseQuestion({
      questionType: "solution", presentationType: "self_assessment", gradingMode: "self_assessment",
      correctAnswer: null, options: [], analysisMarkdown: "完整步骤",
    }));
    const view = render(<QuestionPage data={data} id="question" />);
    fireEvent.click(await screen.findByRole("button", { name: "查看答案与解析" }));

    // 综合题只显示“参考解析”这一份内容，不再有独立参考答案。
    const heading = await screen.findByRole("heading", { name: "参考解析" });
    expect(heading).toBeTruthy();
    expect(screen.queryByRole("heading", { name: "参考答案" })).toBeNull();
    expect(screen.queryByRole("heading", { name: "解析" })).toBeNull();
    await waitFor(() => expect(view.container.querySelector(".practice-answer-value")).toBeNull());
    expect(view.container.querySelector(".practice-answer")!.textContent).toContain("完整步骤");
  });
});

describe("practice interaction closure", () => {
  it("scopes question-report state to the current attempt", async () => {
    HTMLDialogElement.prototype.showModal = function () { this.open = true; };
    HTMLDialogElement.prototype.close = function () { this.open = false; };
    const q1 = { ...session("correct"), currentAttempt: { ...session("correct").currentAttempt, id: "attempt-q1" } };
    const q2 = { ...session("correct"), flowComplete: false, canRepeat: false, currentAttempt: {
      ...session("correct").currentAttempt, id: "attempt-q2", status: "active" as const,
      assessment: undefined, gradingSource: undefined, answerRevealed: false, standard: undefined, explanation: undefined,
    } };
    vi.spyOn(platformApi, "practice").mockResolvedValue(q1);
    vi.spyOn(platformApi, "nextPractice").mockResolvedValue(q2);
    const report = vi.spyOn(platformApi, "reportQuestion").mockResolvedValue({ id: "report", status: "open" });
    render(<PracticePage data={data} id="session" />);

    await screen.findByText("✓ 回答正确");
    fireEvent.click(screen.getByRole("button", { name: "题目有误？" }));
    fireEvent.change(screen.getByLabelText("问题类型"), { target: { value: "analysis_error" } });
    fireEvent.change(screen.getByLabelText("补充说明（可空）"), { target: { value: "Q1 解析问题" } });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    await waitFor(() => expect(report).toHaveBeenCalledWith("attempt-q1", "analysis_error", "Q1 解析问题"));
    expect(await screen.findByText("已收到反馈")).toBeTruthy();
    expect(screen.getByRole("button", { name: "提交" }).hasAttribute("disabled")).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "关闭窗口" }));
    fireEvent.click(screen.getByRole("button", { name: "题目有误？" }));
    expect(screen.getByText("已收到反馈")).toBeTruthy();
    expect(screen.getByLabelText("补充说明（可空）")).toHaveProperty("value", "Q1 解析问题");
    fireEvent.click(screen.getByRole("button", { name: "关闭窗口" }));

    fireEvent.click(screen.getByRole("button", { name: "下一道题" }));
    await waitFor(() => expect(platformApi.nextPractice).toHaveBeenCalledWith("session"));
    fireEvent.click(screen.getByRole("button", { name: "题目有误？" }));
    expect(screen.queryByText("已收到反馈")).toBeNull();
    expect(screen.getByLabelText("问题类型")).toHaveProperty("value", "content_error");
    expect(screen.getByLabelText("补充说明（可空）")).toHaveProperty("value", "");
    expect(screen.getByRole("button", { name: "提交" }).hasAttribute("disabled")).toBe(false);
    fireEvent.change(screen.getByLabelText("补充说明（可空）"), { target: { value: "Q2 题干问题" } });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    await waitFor(() => expect(report).toHaveBeenLastCalledWith("attempt-q2", "content_error", "Q2 题干问题"));
  });

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
    expect(await screen.findByText("当前学习范围内暂无可练错题")).toBeTruthy();
    expect(screen.getByRole("link", { name: "← 返回学习" }).getAttribute("href")).toBe("/study");
    expect(screen.queryByText(/这里只保留/)).toBeNull();
    expect(screen.queryByText(/之后若同一道题/)).toBeNull();
    expect(screen.queryByText("错题练习", { selector: ".eyebrow" })).toBeNull();
  });

  it("marks only mastered question previews", () => {
    const summary = { id: "q", subject: "数学", sourceType: "custom", questionType: "single_choice",
      presentationType: "single_choice", gradingMode: "auto", contentMarkdown: "题干", analysisMarkdown: "",
      correctAnswer: "A", difficulty: 2, revision: 1, learnerQuestionStatus: "mastered" as const };
    const view = render(<QuestionPreviewCard summary={summary} />);
    expect(screen.getByText("✓ 已掌握")).toBeTruthy();
    view.rerender(<QuestionPreviewCard summary={{ ...summary, learnerQuestionStatus: "unseen" }} />);
    expect(screen.queryByText("✓ 已掌握")).toBeNull();
  });

  it("links knowledge point tags only in knowledge-scoped contexts", () => {
    const summary = { id: "q", subject: "数学", sourceType: "custom", questionType: "single_choice",
      presentationType: "single_choice", gradingMode: "auto", contentMarkdown: "题干", analysisMarkdown: "",
      correctAnswer: "A", difficulty: 2, revision: 1,
      knowledgePoints: [{ id: "point", code: "K", name: "数列极限计算", subject: "数学一", section: "",
        chapter: "", description: "", explanation: "", role: "core" }] };
    // 全局题库场景：知识点可能属于 Learner 尚未选择的文集，只展示不链接，避免点击后 404。
    const global = render(<QuestionPreviewCard summary={summary} />);
    expect(screen.getByText("数列极限计算").tagName).toBe("SPAN");
    expect(global.container.querySelector('a[href="/knowledge/point"]')).toBeNull();
    cleanup();
    // 已确定学习范围的页面继续使用可点击 HubLink。
    const scoped = render(<QuestionPreviewCard summary={summary} linkKnowledgePoints />);
    expect(scoped.container.querySelector('a[href="/knowledge/point"]')).toBeTruthy();
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
    // 错题快练与单题重做一样返回错题列表。
    expect(safePracticeReturnTo("wrong_drill")).toBe("/wrong-questions");
  });

  it("shows exam label and every knowledge point tag on the formal practice page", async () => {
    vi.spyOn(platformApi, "practice").mockResolvedValue({ ...session("correct"), currentAttempt: {
      ...session("correct").currentAttempt,
      sourceName: "2022年全国硕士研究生招生考试数学一", examYear: 2022,
      questionNumber: "2022-3", displayQuestionNumber: "3",
      examLabel: "2022年考研数学一真题",
      knowledgePoints: [
        { id: "k1", name: "数列极限计算", role: "core" },
        { id: "k2", name: "函数奇偶性、周期性与单调性", role: "auxiliary" },
      ],
    } });
    const view = render(<PracticePage data={data} id="session" />);
    expect(await screen.findByText("2022年考研数学一真题")).toBeTruthy();
    expect(screen.getByText("第3题")).toBeTruthy();
    // core / auxiliary 都显示，并用不同角色类区分。
    expect(screen.getByText("数列极限计算").classList.contains("core")).toBe(true);
    expect(screen.getByText("函数奇偶性、周期性与单调性").classList.contains("auxiliary")).toBe(true);
    expect(view.container.querySelectorAll(".practice-exam-meta .practice-exam-label")).toHaveLength(1);
  });

  it("keeps the two wrong-question buttons spaced and readable", async () => {
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([{ questionId: "q", targetKnowledgePointId: "k",
      knowledgePointName: "函数", contentMarkdown: "错题", lastGradedAt: "2026-10-06T00:00:00Z", available: true,
      examLabel: "2022年考研数学一真题", questionNumber: "2022-3", displayQuestionNumber: "3",
      knowledgePoints: [{ id: "k", code: "K", name: "数列极限计算", subject: "数学一", section: "",
        chapter: "", description: "", explanation: "", role: "core" }] }]);
    const view = render(<WrongQuestionsPage data={data} />);
    await screen.findByRole("button", { name: "重做这道题" });
    const actions = view.container.querySelector(".wrong-actions")!;
    expect(actions).toBeTruthy();
    expect(actions.children).toHaveLength(2);
    expect(screen.getByRole("button", { name: "重做这道题" }).classList.contains("hub-primary")).toBe(true);
    expect(screen.getByRole("button", { name: "移出错题本" }).classList.contains("secondary")).toBe(true);
    expect(screen.getByText("2022年考研数学一真题")).toBeTruthy();
  });

  it("offers quick wrong-question practice and the recent chapter entry on Study", async () => {
    vi.spyOn(platformApi, "book").mockResolvedValue(bookWithChapter());
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([
      { questionId: "q1", targetKnowledgePointId: "k", knowledgePointName: "函数",
        contentMarkdown: "错题", lastGradedAt: "2026-10-06T00:00:00Z", available: true },
    ]);
    vi.spyOn(platformApi, "progress").mockRejectedValue(new Error("not needed"));
    vi.spyOn(platformApi, "recentChapter").mockResolvedValue({
      status: "last", lastSessionId: "old", bookId: "math", bookName: "考研数学一",
      chapterId: "chapter", chapterName: "函数章", updatedAt: "2026-10-06T08:00:00Z",
    });
    const startWrong = vi.spyOn(platformApi, "startWrongDrill")
      .mockResolvedValue({ ...session(), intent: "wrong_drill" });
    const startChapter = vi.spyOn(platformApi, "startChapterPractice")
      .mockResolvedValue({ ...session(), intent: "chapter_drill" });
    Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
    render(<StudyPage data={data} reload={vi.fn()} />);

    // 没有 active Session 但有最近一次章节练习：顶部卡片显示当前书籍与章节，按钮为“再次练习”。
    // PR7 UI 精修后不再显示“最近练习章节”标签、时间戳、分式进度或按钮箭头。
    const hero = await waitFor(() => {
      const node = document.querySelector(".study-hero")!;
      expect(node.querySelector(".study-hero-chapter")?.textContent).toBe("函数章");
      return node;
    });
    expect(hero.textContent).not.toContain("最近练习章节");
    expect(hero.textContent).not.toContain("最近练习 ");
    expect(hero.textContent).not.toContain("2 / 52");
    const again = screen.getByRole("button", { name: "再次练习" });
    expect(again.textContent).toBe("再次练习");
    fireEvent.click(again);
    await waitFor(() => expect(startChapter).toHaveBeenCalledWith("math", "chapter"));

    // 错题区域：primary 快速练习错题 + secondary 进入错题本。
    expect(await screen.findByText("已保留 1 道错题")).toBeTruthy();
    const quick = screen.getByRole("button", { name: "快速练习错题" });
    expect(quick.hasAttribute("disabled")).toBe(false);
    fireEvent.click(quick);
    await waitFor(() => expect(startWrong).toHaveBeenCalled());
  });

  it("shows the continue entry only for an active chapter session", async () => {
    vi.spyOn(platformApi, "book").mockResolvedValue(bookWithChapter());
    vi.spyOn(platformApi, "wrongQuestions").mockResolvedValue([]);
    vi.spyOn(platformApi, "progress").mockRejectedValue(new Error("not needed"));
    vi.spyOn(platformApi, "recentChapter").mockResolvedValue({
      status: "active", activeSessionId: "active-session", bookId: "math", bookName: "考研数学一",
      chapterId: "chapter", chapterName: "函数章", currentKnowledgePointIndex: 2, knowledgePointCount: 7,
      updatedAt: "2026-10-06T08:00:00Z",
    });
    Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
    render(<StudyPage data={data} reload={vi.fn()} />);

    // active Session：同一个按钮继续该 Session，且不再显示分式进度或箭头。
    const resume = await screen.findByRole("button", { name: "再次练习" });
    expect(resume.textContent).toBe("再次练习");
    expect(screen.queryByText("当前进度：2 / 7")).toBeNull();
    expect(screen.queryByText(/2 \/ 7/)).toBeNull();
    // 0 道错题时快练按钮禁用，避免点击后才报错。
    const quick = screen.getByRole("button", { name: "快速练习错题" });
    expect(quick.hasAttribute("disabled")).toBe(true);
  });
});

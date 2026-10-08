// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { platformApi, type BrowseQuestion, type HubBootstrap, type PageResult, type QuestionDirectoryFacets } from "./api";
import { QuestionDirectoryPage } from "./PlatformApp";
import { isHubPath } from "./navigation";

const data = {
  learner: { id: "learner", username: "learner", displayName: "学习者", revision: 1 },
  canManage: false,
  studyProfile: { pace: "normal", difficulty: "standard", focusMode: "auto", revision: 1,
    selectedBookIds: [], weights: {}, focusedKnowledgePointIds: [], focusedKnowledgePoints: [] },
  worlds: [], questionCatalog: { source: "test", canEdit: false },
  // 全平台题库与 selected Books 解耦：这里故意一个文集都没选。
  bankManifest: [],
} as HubBootstrap;

const facets: QuestionDirectoryFacets = {
  sources: [{ id: "source-1", displayName: "2020年考研数学一真题", sourceType: "real_exam" }],
  examYears: [2021, 2020],
  books: [{ id: "book-1", name: "考研数学一", chapters: [{ id: "chapter-1", name: "第一章" }] }],
};

function browseQuestion(number: number): BrowseQuestion {
  return {
    id: `question-${number}`, subject: "数学一", sourceType: "real_exam", sourceName: "2020年考研数学一真题",
    examYear: 2020, questionNumber: String(number), displayQuestionNumber: String(number),
    questionType: "single_choice", presentationType: "single_choice", gradingMode: "auto",
    contentMarkdown: `第 ${number} 题题干 $x^2$`, analysisMarkdown: `第 ${number} 题解析`,
    correctAnswer: "A", difficulty: 2, revision: 1,
    options: [{ key: "A", text: "甲" }, { key: "B", text: "乙" }],
    // 该知识点属于 Learner 尚未选择的文集：标签只能展示，不能跳到 /knowledge/{id}。
    knowledgePoints: [{ id: `point-${number}`, code: `K${number}`, name: `知识点${number}`,
      subject: "数学一", section: "", chapter: "", description: "", explanation: "", role: "core" }],
  };
}

const questions = Array.from({ length: 20 }, (_, index) => browseQuestion(index + 1));
const page = (content: BrowseQuestion[], pageIndex = 0): PageResult<BrowseQuestion> =>
  ({ content, page: pageIndex, size: 20, totalElements: 22, totalPages: 2 });

/** 每次都在测试内新建 spy：afterEach 的 restoreAllMocks 会还原模块级 spy，不能跨测试复用。 */
function mockDirectory() {
  window.history.replaceState(null, "", "/questions");
  vi.spyOn(platformApi, "questionDirectory").mockImplementation(async (filters) => Number(filters.page) === 1
    ? page([questions[0], questions[1]], 1)
    : page(questions, 0));
  vi.spyOn(platformApi, "questionDirectoryFacets").mockResolvedValue(facets);
  return vi.mocked(platformApi.questionDirectory);
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("QuestionDirectoryPage", () => {
  it("drops the top title block and lets the filters start the page", async () => {
    mockDirectory();
    const view = render(<QuestionDirectoryPage data={data} />);
    await screen.findByText("共 22 道题");

    // 顶部两行（题库 / 全平台已发布正式题目，共 X 道）已删除，且不得用别的标题替代。
    expect(view.container.querySelector(".page-title")).toBeNull();
    expect(view.container.querySelector("h1")).toBeNull();
    expect(view.container.textContent).not.toContain("全平台已发布正式题目");
    expect(view.container.textContent).not.toContain("题库题库");
    // 筛选区是页面第一个内容块；总数只在底部翻页行出现一次。
    const main = view.container.querySelector(".question-bank-page")!;
    expect(main.firstElementChild!.classList.contains("question-directory-filters")).toBe(true);
    expect(screen.getAllByText("共 22 道题")).toHaveLength(1);
  });

  it("loads 20 questions per page and pages with previous/next buttons", async () => {
    const directory = mockDirectory();
    render(<QuestionDirectoryPage data={data} />);

    expect(await screen.findByText("共 22 道题")).toBeTruthy();
    expect(screen.getByText("第 1 / 2 页")).toBeTruthy();
    // 固定 20 / 页：不提供 page-size 自选控件。
    expect(screen.queryByLabelText("每页数量")).toBeNull();
    expect(directory).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0, size: 20 }));

    const previous = screen.getByRole("button", { name: "上一页" }) as HTMLButtonElement;
    const next = screen.getByRole("button", { name: "下一页" }) as HTMLButtonElement;
    expect(previous.disabled).toBe(true);
    fireEvent.click(next);
    expect(await screen.findByText("第 2 / 2 页")).toBeTruthy();
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })));
  });

  it("resets to page zero and sends every filter including the structured question number", async () => {
    const directory = mockDirectory();
    render(<QuestionDirectoryPage data={data} />);
    await screen.findByText("第 1 / 2 页");
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("第 2 / 2 页");

    // “2020-7” 原样传给后端，由共享解析器展开成 exam_year + question_number。
    fireEvent.change(screen.getByLabelText("关键词"), { target: { value: "2020-7" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ query: "2020-7", page: 0, size: 20 })));

    fireEvent.change(screen.getByLabelText("来源"), { target: { value: "source-1" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ sourceId: "source-1", page: 0 })));
    fireEvent.change(screen.getByLabelText("年份"), { target: { value: "2020" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ examYear: "2020", page: 0 })));
    fireEvent.change(screen.getByLabelText("题型"), { target: { value: "solution" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ questionType: "solution", page: 0 })));
    fireEvent.change(screen.getByLabelText("难度"), { target: { value: "3" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ difficulty: "3", page: 0 })));
    fireEvent.change(screen.getByLabelText("文集"), { target: { value: "book-1" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ bookId: "book-1", page: 0 })));
    fireEvent.change(screen.getByLabelText("章节"), { target: { value: "chapter-1" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ chapterId: "chapter-1", page: 0 })));
    fireEvent.change(screen.getByLabelText("知识点"), { target: { value: "极限" } });
    await waitFor(() => expect(directory).toHaveBeenLastCalledWith(
      expect.objectContaining({ knowledge: "极限", page: 0 })));
  });

  it("shows an inline preview through the shared read-only renderer", async () => {
    mockDirectory();
    const question = vi.spyOn(platformApi, "question").mockResolvedValue(questions[0]);
    const view = render(<QuestionDirectoryPage data={data} />);
    await screen.findByText("共 22 道题");

    fireEvent.click(screen.getAllByRole("button", { name: "预览" })[0]);
    await waitFor(() => expect(question).toHaveBeenCalledWith("question-1"));
    // 预览渲染在卡片内部，复用共享只读渲染器（ReadonlyQuestion）。
    await waitFor(() => expect(view.container.querySelector(".inline-question-preview .question-meta")).toBeTruthy());
    expect(screen.getAllByText(/难度 2/).length).toBeGreaterThan(0);
  });

  it("routes the detail call to action to /questions/{id}", async () => {
    mockDirectory();
    render(<QuestionDirectoryPage data={data} />);
    await screen.findByText("共 22 道题");

    const link = screen.getAllByRole("link", { name: "查看答案与解析" })[0];
    expect(link.getAttribute("href")).toBe("/questions/question-1");
    expect(isHubPath("/questions")).toBe(true);
    expect(isHubPath("/questions/question-1")).toBe(true);
  });

  it("never links knowledge point tags of the global question bank", async () => {
    mockDirectory();
    render(<QuestionDirectoryPage data={data} />);
    await screen.findByText("共 22 道题");

    // 全局题库的知识点可能属于未选择的文集，标签只做展示，不产生不可访问的链接。
    expect(screen.getByText("知识点1").tagName).toBe("SPAN");
    expect(screen.queryByRole("link", { name: "知识点1" })).toBeNull();
  });

  it("renders an empty state when nothing matches", async () => {
    window.history.replaceState(null, "", "/questions");
    vi.spyOn(platformApi, "questionDirectory").mockResolvedValue(
      { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    vi.spyOn(platformApi, "questionDirectoryFacets").mockResolvedValue({ sources: [], examYears: [], books: [] });
    render(<QuestionDirectoryPage data={data} />);

    expect(await screen.findByText("没有符合条件的题目。")).toBeTruthy();
    expect(screen.getByText("共 0 道题")).toBeTruthy();
  });
});

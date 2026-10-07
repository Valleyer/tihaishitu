// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QuestionPage, SourcePage, ImportPage } from "./ManagementApp";
import { manageApi, type ManageUser, type PageResult, type QuestionSourceView, type QuestionView } from "./api";

vi.mock("./api", () => ({
  ManageHttpError: class ManageHttpError extends Error {},
  manageApi: {
    questions: vi.fn(),
    question: vi.fn(),
    reviewQuestion: vi.fn(),
    saveQuestion: vi.fn(),
    createQuestion: vi.fn(),
    submitQuestion: vi.fn(),
    knowledge: vi.fn(),
    sources: vi.fn(),
    createSource: vi.fn(),
    saveSource: vi.fn(),
    importQuestionBatch: vi.fn(),
  },
}));
const reviewer: ManageUser = {
  id: "reviewer-id",
  username: "reviewer",
  displayName: "审核者",
  status: "active",
  roles: ["REVIEWER"],
  revision: 1,
};

const questionsMock = vi.mocked(manageApi.questions);
const questionMock = vi.mocked(manageApi.question);
const reviewMock = vi.mocked(manageApi.reviewQuestion);
const sourcesMock = vi.mocked(manageApi.sources);
const saveSourceMock = vi.mocked(manageApi.saveSource);
const importBatchMock = vi.mocked(manageApi.importQuestionBatch);

function question(index: number, status = "pending_review"): QuestionView {
  return {
    id: `00000000-0000-0000-0000-${String(index).padStart(12, "0")}`,
    subject: "数学一",
    sourceType: "real_exam",
    sourceId: "source-id",
    sourceName: "2026年数学一",
    examYear: 2026,
    questionNumber: String(index),
    // 后端格式化的展示题号；列表只用它，raw questionNumber 保留给编辑器。
    displayQuestionNumber: String(index),
    questionType: "single_choice",
    presentationType: "single_choice",
    gradingMode: "auto",
    content: `第 ${index} 题`,
    analysis: "解析",
    difficulty: 2,
    status,
    createdBy: "contributor-id",
    creatorName: "贡献者",
    revision: 1,
    options: [
      { key: "A", text: "正确", correct: true, sortOrder: 0 },
      { key: "B", text: "错误", correct: false, sortOrder: 1 },
    ],
    knowledgePoints: [{ id: "knowledge-id", code: "M1-H01-001", name: "函数定义", role: "core", sortOrder: 0 }],
  };
}

function result(content: QuestionView[], page: number, totalElements: number, totalPages: number): PageResult<QuestionView> {
  return { content, page, size: 20, totalElements, totalPages };
}

beforeEach(() => {
  questionsMock.mockReset();
  questionMock.mockReset();
  reviewMock.mockReset();
  sourcesMock.mockReset();
  saveSourceMock.mockReset();
  importBatchMock.mockReset();
  vi.mocked(manageApi.saveQuestion).mockReset();
  vi.mocked(manageApi.createQuestion).mockReset();
  vi.mocked(manageApi.knowledge).mockReset();
  questionsMock.mockResolvedValue(result(Array.from({ length: 20 }, (_, index) => question(index + 1)), 0, 22, 2));
});

describe("SourcePage", () => {
  it("lists, filters and edits global sources with fixed pagination", async () => {
    const source: QuestionSourceView = { id:"source-1",sourceType:"real_exam",canonicalName:"正式名",
      displayName:"展示名",status:"active",revision:1,questionCount:3,updatedAt:"2026-10-07T00:00:00Z" };
    sourcesMock.mockResolvedValue({content:[source],page:0,size:20,totalElements:1,totalPages:1});
    saveSourceMock.mockResolvedValue({...source,displayName:"新展示名",revision:2});
    render(<SourcePage fail={vi.fn()}/>);
    expect(await screen.findByText("共 1 个全局题目来源")).toBeTruthy();
    fireEvent.change(screen.getByPlaceholderText("搜索展示名称 / 正式名称"),{target:{value:"展示"}});
    await waitFor(()=>expect(sourcesMock).toHaveBeenLastCalledWith(expect.objectContaining({query:"展示",size:20})));
    fireEvent.click(screen.getByRole("button",{name:/展示名/}));
    fireEvent.change(screen.getByLabelText("展示名称"),{target:{value:"新展示名"}});
    fireEvent.click(screen.getByRole("button",{name:"保存来源"}));
    await waitFor(()=>expect(saveSourceMock).toHaveBeenCalledWith(expect.objectContaining({displayName:"新展示名",revision:1})));
  });
});

afterEach(cleanup);

function questionBatch(schemaVersion: string) {
  return JSON.stringify({
    schemaVersion,
    publish: true,
    batch: { subject: "数学一", sourceType: "real_exam", sourceName: "2026年数学一", examYear: 2026 },
    questions: [],
  });
}

describe("ImportPage question batch compatibility", () => {
  const result = (schemaVersion: string) => ({
    schemaVersion, importId: "import-id", published: true, subject: "数学一",
    sourceType: "real_exam", sourceName: "2026年数学一", examYear: 2026,
    questionCount: 1, optionCount: 2, relationCount: 1, createdQuestions: 1, updatedQuestions: 0,
  });

  it("submits the canonical v4 question batch", async () => {
    importBatchMock.mockResolvedValue(result("global-question-batch/v4"));
    render(<ImportPage fail={vi.fn()} />);
    fireEvent.change(screen.getByPlaceholderText("粘贴 JSON，或选择文件…"),
      { target: { value: questionBatch("global-question-batch/v4") } });
    const confirm = screen.getByRole("button", { name: "确认导入" }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(importBatchMock).toHaveBeenCalledTimes(1));
  });

  it("actually submits a v3 historical compatibility batch instead of only saying it is supported", async () => {
    importBatchMock.mockResolvedValue(result("global-question-batch/v3"));
    render(<ImportPage fail={vi.fn()} />);
    fireEvent.change(screen.getByPlaceholderText("粘贴 JSON，或选择文件…"),
      { target: { value: questionBatch("global-question-batch/v3") } });
    expect(screen.getByText(/v3 历史兼容格式/)).toBeTruthy();
    const confirm = screen.getByRole("button", { name: "确认导入" }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(importBatchMock).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("2026年数学一 · global-question-batch/v3")).toBeTruthy();
  });

  it("keeps an unsupported older question batch disabled", () => {
    render(<ImportPage fail={vi.fn()} />);
    fireEvent.change(screen.getByPlaceholderText("粘贴 JSON，或选择文件…"),
      { target: { value: questionBatch("global-question-batch/v2") } });
    expect((screen.getByRole("button", { name: "确认导入" }) as HTMLButtonElement).disabled).toBe(true);
    expect(importBatchMock).not.toHaveBeenCalled();
  });
});

describe("QuestionPage pagination", () => {
  it("shows totals, pages through results, disables boundary buttons, and owns a scroll container", async () => {
    questionsMock.mockImplementation(async (filters) => Number(filters.page) === 1
      ? result([question(21), question(22)], 1, 22, 2)
      : result(Array.from({ length: 20 }, (_, index) => question(index + 1)), 0, 22, 2));

    const { container } = render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    expect(await screen.findByText("共 22 道")).toBeTruthy();
    expect(screen.getByText("第 1 / 2 页")).toBeTruthy();
    expect(screen.queryByLabelText("每页数量")).toBeNull();
    expect(container.querySelector(".question-management-table.data-table")).toBeTruthy();

    const previous = screen.getByRole("button", { name: "上一页" }) as HTMLButtonElement;
    const next = screen.getByRole("button", { name: "下一页" }) as HTMLButtonElement;
    expect(previous.disabled).toBe(true);
    expect(next.disabled).toBe(false);
    fireEvent.click(next);

    expect(await screen.findByText("第 2 / 2 页")).toBeTruthy();
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1, size: 20 })));
    expect((screen.getByRole("button", { name: "上一页" }) as HTMLButtonElement).disabled).toBe(false);
    expect((screen.getByRole("button", { name: "下一页" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("returns to page zero when search or question type changes", async () => {
    questionsMock.mockImplementation(async (filters) => result([question(Number(filters.page) * 20 + 1)], Number(filters.page), 41, 3));
    render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    await screen.findByText("第 1 / 3 页");
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })));

    fireEvent.change(screen.getByPlaceholderText("搜索题干 / 来源 / 题号"), { target: { value: "极限" } });
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ query: "极限", page: 0 })));

    fireEvent.change(screen.getByRole("combobox", { name: "题型" }), { target: { value: "solution" } });
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ questionType: "solution", page: 0 })));
  });

  it("edits correctness through options and previews the single complete analysis", async () => {
    const item = question(1, "draft");
    questionsMock.mockResolvedValue(result([item], 0, 1, 1));
    questionMock.mockResolvedValue(item);
    render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "编辑" }));
    expect(await screen.findByText("预览解析")).toBeTruthy();
    expect(screen.queryByText("标准答案（JSON）")).toBeNull();
    expect(screen.getAllByText("正确").length).toBeGreaterThan(0);
    fireEvent.click(screen.getByText("预览解析"));
    expect(screen.getByText("解析", { selector: ".markdown-preview p" })).toBeTruthy();
  });

  it("resets to page zero and always requests pending items when review mode changes", async () => {
    questionsMock.mockImplementation(async (filters) => result([question(Number(filters.page) * 20 + 1)], Number(filters.page), 21, 2));
    const view = render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    await screen.findByText("第 1 / 2 页");
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await screen.findByText("第 2 / 2 页");
    const callsBeforeReviewMode = questionsMock.mock.calls.length;

    view.rerender(<QuestionPage user={reviewer} fail={vi.fn()} reviewOnly />);
    expect(await screen.findByText("待审核共 21 道")).toBeTruthy();
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({
      status: "pending_review",
      page: 0,
      size: 20,
    })));
    expect(questionsMock.mock.calls.slice(callsBeforeReviewMode)
      .every(([filters]) => filters.status === "pending_review")).toBe(true);
  });

  it("replaces the status filter with the question type filter and never sends status", async () => {
    render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    await screen.findByText("共 22 道");

    // 普通题目管理页不再提供“全部状态”筛选。
    expect(screen.queryByLabelText("题目状态")).toBeNull();
    const typeFilter = screen.getByLabelText("题型") as HTMLSelectElement;
    expect(typeFilter.value).toBe("");
    expect(screen.getByRole("option", { name: "全部题型" })).toBeTruthy();

    fireEvent.change(typeFilter, { target: { value: "multiple_choice" } });
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({
      questionType: "multiple_choice",
      status: undefined,
      page: 0,
    })));
    // 普通页的任何一次请求都不能带上 status。
    expect(questionsMock.mock.calls.every(([filters]) => filters.status === undefined)).toBe(true);
  });

  it("passes the structured 2020-7 query straight to the backend", async () => {
    render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    await screen.findByText("共 22 道");

    fireEvent.change(screen.getByPlaceholderText("搜索题干 / 来源 / 题号"), { target: { value: "2020-7" } });
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({
      query: "2020-7", page: 0,
    })));
    // 结构化解析是后端共享解析器的职责，前端只原样提交。
    expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ status: undefined }));
  });

  it("shows a visible non-blocking success notice after creating a draft and keeps it across the remount", async () => {
    const creator = { ...reviewer, id: "creator-id", username: "contributor", displayName: "贡献者" };
    questionsMock.mockResolvedValue(result([], 0, 0, 0));
    const knowledgeMock = vi.mocked(manageApi.knowledge);
    const createMock = vi.mocked(manageApi.createQuestion);
    knowledgeMock.mockResolvedValue({
      content: [{ id: "knowledge-id", code: "M1-H01-001", name: "函数定义", subject: "数学一", section: "高等数学",
        chapter: "函数", defaultRole: "core", status: "active", description: "", explanation: "",
        aliases: [], questionCount: 1, books: [], revision: 1 }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    createMock.mockResolvedValue({ ...question(3, "draft"), id: "created-question", sourceId: "source-id" });

    render(<QuestionPage user={creator} fail={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "新建题目" }));
    // 抽屉里有两个“搜索”按钮（来源 / 知识点），取知识点绑定那一个。
    fireEvent.click(screen.getAllByRole("button", { name: "搜索" })[1]);
    fireEvent.click(await screen.findByRole("button", { name: /M1-H01-001/ }));
    fireEvent.change(screen.getByLabelText(/题干（Markdown/), { target: { value: "新建题干" } });
    fireEvent.click(screen.getByRole("button", { name: "保存草稿" }));

    // 成功提示同时出现在列表页与抽屉内，都是 role="status"；关键是它必须留得住。
    await waitFor(() => expect(screen.getAllByRole("status").length).toBeGreaterThan(0));
    const notices = screen.getAllByRole("status").map(node => node.textContent).join("|");
    expect(notices).toContain("草稿已创建");
    expect(createMock).toHaveBeenCalledTimes(1);
    // 保存成功后 key 从 "new" 变成题目 ID，提示不能因此消失。
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(screen.getAllByRole("status").map(node => node.textContent).join("|")).toContain("草稿已创建");
  });

  it("shows a visible success notice after saving an existing question", async () => {
    const item = question(1, "draft");
    questionsMock.mockResolvedValue(result([item], 0, 1, 1));
    questionMock.mockResolvedValue(item);
    const saveMock = vi.mocked(manageApi.saveQuestion);
    saveMock.mockResolvedValue({ ...item, revision: 2 });

    render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    fireEvent.click(await screen.findByRole("button", { name: "编辑" }));
    fireEvent.click(await screen.findByRole("button", { name: "保存修改" }));

    await waitFor(() => expect(screen.getAllByRole("status").length).toBeGreaterThan(0));
    expect(screen.getAllByRole("status").map(node => node.textContent).join("|")).toContain("已保存修改");
    expect(saveMock).toHaveBeenCalledWith(expect.objectContaining({ id: item.id, revision: 1 }));
  });

  it("shows the backend-formatted display question number instead of concatenating the raw one", async () => {
    // 历史数据：raw "2020-7" + examYear 2020 不能被前端拼成 "2020-2020-7"。
    const legacy = { ...question(7), questionNumber: "2020-7", displayQuestionNumber: "7" };
    const plain = { ...question(9), questionNumber: "9", displayQuestionNumber: "9" };
    questionsMock.mockResolvedValue(result([legacy, plain], 0, 2, 1));

    const view = render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    await screen.findByText("共 2 道");

    expect(await screen.findByText("2026-7")).toBeTruthy();
    expect(screen.queryByText("2026-2020-7")).toBeNull();
    expect(screen.getByText("2026-9")).toBeTruthy();
    // 编辑器仍使用原始 questionNumber：打开后输入框里必须是 raw 值。
    questionMock.mockResolvedValue(legacy);
    fireEvent.click(screen.getAllByRole("button", { name: "编辑" })[0]);
    const rawInput = await screen.findByDisplayValue("2020-7");
    expect(rawInput).toBeTruthy();
    expect(view.container.querySelector('input[value="7"]')).toBeNull();
  });

  it("uses the display question number in the review list too", async () => {
    const legacy = { ...question(21), questionNumber: "2026-21", displayQuestionNumber: "21" };
    questionsMock.mockResolvedValue(result([legacy], 0, 1, 1));

    render(<QuestionPage user={reviewer} fail={vi.fn()} reviewOnly />);
    expect(await screen.findByText("2026 · 21")).toBeTruthy();
    expect(screen.queryByText("2026 · 2026-21")).toBeNull();
  });

  it("steps back and reloads after reviewing the last item on a page", async () => {
    let reviewed = false;
    const requestedPagesAfterReview: number[] = [];
    questionsMock.mockImplementation(async (filters) => {
      const requestedPage = Number(filters.page);
      if (reviewed) requestedPagesAfterReview.push(requestedPage);
      if (requestedPage === 1) return reviewed ? result([], 1, 20, 1) : result([question(21)], 1, 21, 2);
      return result(Array.from({ length: 20 }, (_, index) => question(index + 1)), 0,
        reviewed ? 20 : 21, reviewed ? 1 : 2);
    });
    questionMock.mockResolvedValue(question(21));
    reviewMock.mockImplementation(async (item) => {
      reviewed = true;
      return { ...item, status: "published", revision: item.revision + 1 };
    });

    render(<QuestionPage user={reviewer} fail={vi.fn()} reviewOnly />);
    await screen.findByText("第 1 / 2 页");
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    const rowLabel = await screen.findByText("2026 · 21");
    fireEvent.click(rowLabel.closest("button")!);
    fireEvent.click(await screen.findByRole("button", { name: "审核通过并发布" }));

    expect(await screen.findByText("第 1 / 1 页")).toBeTruthy();
    expect(screen.getByText("待审核共 20 道")).toBeTruthy();
    await waitFor(() => expect(requestedPagesAfterReview).toEqual([1, 0]));
    expect(questionsMock.mock.calls.every(([filters]) => filters.status === "pending_review")).toBe(true);
  });
});

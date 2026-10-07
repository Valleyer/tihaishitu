// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QuestionPage, SourcePage } from "./ManagementApp";
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

function question(index: number, status = "pending_review"): QuestionView {
  return {
    id: `00000000-0000-0000-0000-${String(index).padStart(12, "0")}`,
    subject: "数学一",
    sourceType: "real_exam",
    sourceId: "source-id",
    sourceName: "2026年数学一",
    examYear: 2026,
    questionNumber: String(index),
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

  it("returns to page zero when search or status changes", async () => {
    questionsMock.mockImplementation(async (filters) => result([question(Number(filters.page) * 20 + 1)], Number(filters.page), 41, 3));
    render(<QuestionPage user={reviewer} fail={vi.fn()} />);
    await screen.findByText("第 1 / 3 页");
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })));

    fireEvent.change(screen.getByPlaceholderText("搜索题干 / 来源 / 题号"), { target: { value: "极限" } });
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ query: "极限", page: 0 })));

    fireEvent.change(screen.getByRole("combobox", { name: "题目状态" }), { target: { value: "published" } });
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ status: "published", page: 0 })));
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

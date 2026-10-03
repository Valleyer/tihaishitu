// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { QuestionPage } from "./ManagementApp";
import { manageApi, type ManageUser, type PageResult, type QuestionView } from "./api";

vi.mock("./api", () => ({
  manageApi: {
    questions: vi.fn(),
    question: vi.fn(),
    reviewQuestion: vi.fn(),
    saveQuestion: vi.fn(),
    createQuestion: vi.fn(),
    submitQuestion: vi.fn(),
    knowledge: vi.fn(),
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

function question(index: number, status = "pending_review"): QuestionView {
  return {
    id: `00000000-0000-0000-0000-${String(index).padStart(12, "0")}`,
    subject: "数学一",
    sourceType: "real_exam",
    sourceName: "2026年数学一",
    examYear: 2026,
    questionNumber: String(index),
    questionType: "single_choice",
    presentationType: "single_choice",
    gradingMode: "auto",
    content: `第 ${index} 题`,
    standardAnswer: "A",
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
  questionsMock.mockResolvedValue(result(Array.from({ length: 20 }, (_, index) => question(index + 1)), 0, 22, 2));
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
    expect(container.querySelector(".data-table.manage-question-list")).toBeTruthy();

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

    fireEvent.change(screen.getByRole("combobox"), { target: { value: "published" } });
    await waitFor(() => expect(questionsMock).toHaveBeenLastCalledWith(expect.objectContaining({ status: "published", page: 0 })));
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
    fireEvent.click(await screen.findByRole("button", { name: "审核通过" }));

    expect(await screen.findByText("第 1 / 1 页")).toBeTruthy();
    expect(screen.getByText("待审核共 20 道")).toBeTruthy();
    expect(requestedPagesAfterReview).toEqual([1, 0]);
    expect(questionsMock.mock.calls.every(([filters]) => filters.status === "pending_review")).toBe(true);
  });
});

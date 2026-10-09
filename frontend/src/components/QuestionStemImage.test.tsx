// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { QuestionStemImage } from "./QuestionStemImage";

afterEach(cleanup);

describe("QuestionStemImage", () => {
  it("does not render a wrapper without a source", () => {
    const view = render(<QuestionStemImage />);
    // 无图时零占位：不能留下空 wrapper / 空容器。
    expect(view.container.innerHTML).toBe("");
    expect(view.container.querySelector(".question-stem-image")).toBeNull();
  });

  it("does not render a wrapper for null or blank sources", () => {
    const nullView = render(<QuestionStemImage src={null} />);
    expect(nullView.container.innerHTML).toBe("");
    const blankView = render(<QuestionStemImage src="" />);
    expect(blankView.container.innerHTML).toBe("");
  });

  it("renders an accessible image with a source", () => {
    render(<QuestionStemImage src="/api/v1/question-images/asset-a" />);
    const image = screen.getByRole("img", { name: "题目配图" }) as HTMLImageElement;
    expect(image.getAttribute("src")).toBe("/api/v1/question-images/asset-a");
    expect(image.className).toBe("question-stem-image");
  });
});

// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { QuestionStemImage } from "./QuestionStemImage";

afterEach(cleanup);

describe("QuestionStemImage", () => {
  it("does not render a wrapper without a source", () => {
    const view = render(<QuestionStemImage />);
    expect(view.container).toBeEmptyDOMElement();
  });

  it("renders an accessible image with a source", () => {
    render(<QuestionStemImage src="/api/v1/question-images/asset-a" />);
    expect(screen.getByRole("img", { name: "题目配图" })).toHaveAttribute(
      "src", "/api/v1/question-images/asset-a",
    );
  });
});

// @vitest-environment jsdom

import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { RichText } from "./RichText";

describe("RichText markdown tables", () => {
  it("renders a standard pipe table and keeps inline LaTeX inside cells", () => {
    const view = render(<RichText>{`| 指令 | 位段 | 说明 |
| --- | --- | --- |
| add | 31~25 | $x^2$ |
| slli | 24~20 | 立即数 |`}</RichText>);

    const table = view.container.querySelector("table");
    expect(table).toBeTruthy();
    expect(table?.querySelectorAll("th")).toHaveLength(3);
    expect(table?.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(table?.textContent).toContain("slli");
    expect(table?.querySelector(".katex")).toBeTruthy();
    expect(view.container.querySelector(".rich-table-scroll")).toBeTruthy();
  });
});

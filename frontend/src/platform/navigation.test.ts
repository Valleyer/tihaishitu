// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from "vitest";
import { isHubPath, navigate } from "./navigation";

describe("hub navigation", () => {
  beforeEach(() => {
    Object.defineProperty(window, "scrollTo", { value: vi.fn(), configurable: true });
    history.replaceState(null, "", "/");
  });

  it("keeps hub routes in the current document", () => {
    const push = vi.spyOn(history, "pushState");
    navigate("/statistics?days=30");
    expect(push).toHaveBeenCalled();
    expect(location.pathname).toBe("/statistics");
    expect(isHubPath("/knowledge/example")).toBe(true);
    // PR4：知识目录仍是 /books，题库是新的 /questions，两者都在 Hub 内。
    expect(isHubPath("/books")).toBe(true);
    expect(isHubPath("/questions")).toBe(true);
    expect(isHubPath("/questions/example")).toBe(true);
    expect(isHubPath("/worlds/ancient-official")).toBe(false);
    expect(isHubPath("/manage")).toBe(false);
  });
});

// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { Game } from "../domain/types";
import { Journal } from "./Records";

afterEach(cleanup);

const game = (journal: unknown, config?: unknown) => ({ journal, config }) as Game;

describe("journal defensive rendering", () => {
  it("renders an empty state for missing or invalid legacy journal data", () => {
    const { rerender } = render(<Journal game={game(undefined)} />);
    expect(screen.getByText("尚无札记")).toBeTruthy();
    rerender(<Journal game={game({ legacy: true }, {})} />);
    expect(screen.getByText("尚无札记")).toBeTruthy();
  });

  it("accepts a legacy config without pace and caps the newest entries at 150", () => {
    const journal = Array.from({ length: 151 }, (_, index) => ({
      id: `entry-${index}`,
      day: index,
      kind: "journal",
      title: `札记 ${index}`,
      text: "内容",
    }));
    const { container } = render(<Journal game={game(journal, {})} />);
    expect(container.querySelectorAll("article")).toHaveLength(150);
    expect(screen.getByText("札记 150")).toBeTruthy();
    expect(screen.queryByText("札记 0")).toBeNull();
  });
});

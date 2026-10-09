// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { Modal } from "./Modal";

beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  HTMLDialogElement.prototype.close = function () { this.open = false; };
});

afterEach(cleanup);

describe("Modal subtitle", () => {
  it("does not invent an 案头文书 fallback", () => {
    render(<Modal title="故人录" close={() => undefined}>正文</Modal>);
    expect(screen.queryByText(/案头文书/)).toBeNull();
    expect(screen.getByRole("heading", { name: "故人录" })).toBeTruthy();
  });

  it("keeps an explicitly provided subtitle", () => {
    render(
      <Modal title="落笔入世" subtitle="前尘未定 · 一念入世" close={() => undefined}>
        正文
      </Modal>,
    );
    expect(screen.getByText("前尘未定 · 一念入世")).toBeTruthy();
  });
});

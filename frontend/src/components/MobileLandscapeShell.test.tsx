// @vitest-environment jsdom

import { act, cleanup, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MobileLandscapeShell, MOBILE_LANDSCAPE_CLASS, PHONE_PORTRAIT_QUERY } from "./MobileLandscapeShell";

/**
 * PR12 forced landscape shell 的判定 / 激活 / 清理行为测试。
 *
 * jsdom 没有真实 layout，因此这里验证的是「激活判定 + 根节点 class + DOM 稳定性 +
 * 滚动容器复位」这些可被自动化断言的部分；旋转几何与真实滚动由浏览器 Device
 * Emulation 验收覆盖（见 PR 报告）。
 */

type MediaState = {
  "phone portrait": boolean;
  "pointer coarse": boolean;
  touchPoints: number;
};

let state: MediaState;
const listeners = new Map<string, Set<(event: { matches: boolean; media: string }) => void>>();

function fire(media: string, matches: boolean) {
  for (const listener of listeners.get(media) ?? []) listener({ matches, media });
}

beforeEach(() => {
  state = { "phone portrait": true, "pointer coarse": false, touchPoints: 5 };
  listeners.clear();
  Object.defineProperty(window, "matchMedia", {
    configurable: true,
    writable: true,
    value: (query: string) => {
      const matches = query === PHONE_PORTRAIT_QUERY ? state["phone portrait"] : state["pointer coarse"];
      if (!listeners.has(query)) listeners.set(query, new Set());
      return {
        matches,
        media: query,
        onchange: null,
        addEventListener: (_: string, listener: (event: { matches: boolean; media: string }) => void) => {
          listeners.get(query)!.add(listener);
        },
        removeEventListener: (_: string, listener: (event: { matches: boolean; media: string }) => void) => {
          listeners.get(query)!.delete(listener);
        },
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      };
    },
  });
  Object.defineProperty(navigator, "maxTouchPoints", { configurable: true, get: () => state.touchPoints });
  history.replaceState(null, "", "/");
});

afterEach(() => {
  cleanup();
  document.documentElement.classList.remove(MOBILE_LANDSCAPE_CLASS);
});

const isActive = () => document.documentElement.classList.contains(MOBILE_LANDSCAPE_CLASS);

/** 触发一次 matchMedia 变化并让 React 处理更新。 */
function changeOrientation(matches: boolean) {
  act(() => {
    state["phone portrait"] = matches;
    fire(PHONE_PORTRAIT_QUERY, matches);
  });
}

function go(path: string) {
  act(() => {
    history.pushState(null, "", path);
    window.dispatchEvent(new Event("hub:navigate"));
  });
}

/** 极简可见子节点：用于验证旋转开关不会 remount 业务子树。 */
let childMounts = 0;
let childUnmounts = 0;
function Child({ label }: { label: string }) {
  useEffect(() => {
    childMounts += 1;
    return () => { childUnmounts += 1; };
  }, []);
  return <p>{label}</p>;
}

describe("MobileLandscapeShell activation", () => {
  it("1. does not activate on /login", () => {
    history.replaceState(null, "", "/login");
    render(<MobileLandscapeShell><Child label="登录" /></MobileLandscapeShell>);
    expect(isActive()).toBe(false);
  });

  it("2. does not activate on /register", () => {
    history.replaceState(null, "", "/register");
    render(<MobileLandscapeShell><Child label="注册" /></MobileLandscapeShell>);
    expect(isActive()).toBe(false);
  });

  it("3. activates on a phone portrait after login", () => {
    render(<MobileLandscapeShell><Child label="首页" /></MobileLandscapeShell>);
    expect(isActive()).toBe(true);
  });

  it("4. does not activate in real landscape", () => {
    state["phone portrait"] = false;
    render(<MobileLandscapeShell><Child label="首页" /></MobileLandscapeShell>);
    expect(isActive()).toBe(false);
  });

  it("5. does not activate for a narrow desktop window without touch", () => {
    state.touchPoints = 0;
    state["pointer coarse"] = false;
    render(<MobileLandscapeShell><Child label="首页" /></MobileLandscapeShell>);
    expect(isActive()).toBe(false);
  });
});

describe("MobileLandscapeShell route changes", () => {
  it("6. activates when leaving /login for the app", () => {
    history.replaceState(null, "", "/login");
    render(<MobileLandscapeShell><Child label="应用" /></MobileLandscapeShell>);
    expect(isActive()).toBe(false);
    go("/");
    expect(isActive()).toBe(true);
  });

  it("7. clears the root class when returning to /login", () => {
    render(<MobileLandscapeShell><Child label="应用" /></MobileLandscapeShell>);
    expect(isActive()).toBe(true);
    go("/login");
    expect(isActive()).toBe(false);
  });
});

describe("MobileLandscapeShell DOM stability", () => {
  it("8. keeps the same child mounted across orientation changes", () => {
    childMounts = 0;
    childUnmounts = 0;
    render(<MobileLandscapeShell><Child label="World 运行中" /></MobileLandscapeShell>);
    expect(screen.getByText("World 运行中")).toBeTruthy();
    expect(childMounts).toBeGreaterThan(0);

    changeOrientation(false);
    expect(isActive()).toBe(false);
    changeOrientation(true);
    expect(isActive()).toBe(true);

    expect(childUnmounts).toBe(0);
    expect(screen.getByText("World 运行中")).toBeTruthy();
  });

  it("9. removes the root class on unmount", () => {
    const { unmount } = render(<MobileLandscapeShell><Child label="应用" /></MobileLandscapeShell>);
    expect(isActive()).toBe(true);
    unmount();
    expect(isActive()).toBe(false);
  });
});

describe("MobileLandscapeShell scroll container", () => {
  it("10. resets the frame scroll position after a route change", () => {
    const scrollTo = vi.fn();
    const { container } = render(<MobileLandscapeShell><Child label="应用" /></MobileLandscapeShell>);
    const frame = container.querySelector<HTMLDivElement>(".mobile-landscape-frame");
    expect(frame).toBeTruthy();
    frame!.scrollTo = scrollTo;

    go("/questions");
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, left: 0 });
  });
});

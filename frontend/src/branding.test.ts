import { describe, expect, it } from "vitest";
import html from "../index.html?raw";
import manifestSource from "../public/site.webmanifest?raw";
import app from "./App.tsx?raw";
import exploration from "./components/Exploration.tsx?raw";
import platform from "./platform/PlatformApp.tsx?raw";

describe("launch branding and navigation", () => {
  it("publishes the Wan Jing Academy icons and manifest while retaining the hub name", () => {
    const manifest = JSON.parse(manifestSource);
    expect(html).toContain("<title>万境书院</title>");
    expect(html).toContain('rel="manifest" href="/site.webmanifest"');
    expect(html).toContain("/favicon-16x16.png");
    expect(html).toContain("/favicon-32x32.png");
    expect(html).toContain("/apple-touch-icon.png");
    expect(manifest.name).toBe("万境书院");
    expect(manifest.icons.map((icon: { src: string }) => icon.src)).toEqual([
      "/android-chrome-192x192.png", "/android-chrome-512x512.png",
    ]);
    expect(platform).toContain("万境中枢");
    expect(platform).not.toContain("world-shell-home");
  });

  it("keeps one reward presentation and removes the two obsolete answering controls", () => {
    expect(app).not.toContain("暂回世界");
    expect(app).not.toContain("放下本轮");
    expect(exploration).toContain("通关奖励");
    expect(exploration).not.toContain("基础奖励");
    expect(exploration).not.toContain("圆满奖励");
    expect(exploration).not.toContain("60分奖励");
    expect(exploration).not.toContain("100分奖励");
  });
});

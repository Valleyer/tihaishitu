import { describe, expect, it } from "vitest";
import { resolveApiBaseUrl } from "./apiBase";

describe("resolveApiBaseUrl", () => {
  const origin = "http://127.0.0.1:5173";

  it("uses the same-origin API path by default", () => {
    expect(resolveApiBaseUrl(undefined, origin)).toBe("/api/v1");
    expect(resolveApiBaseUrl("/api/v1", origin)).toBe("/api/v1");
  });

  it("rejects an absolute cross-origin API URL", () => {
    expect(() => resolveApiBaseUrl("http://localhost:12345/api/v1", origin))
      .toThrow("万境求知 Learner Session 要求浏览器 API 与前端同源");
  });
});

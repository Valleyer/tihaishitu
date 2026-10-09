// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { manageApi } from "./api";

/**
 * 管理 API 的请求层只在这里被真实执行（其余管理测试都 mock 掉 manageApi）。
 * 题干图片上传必须走 multipart：手工写入 application/json 会让浏览器无法
 * 生成带 boundary 的 Content-Type，后端解析 multipart 直接失败。
 */
describe("manageApi request headers", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    // 已有 CSRF Cookie 时不会额外请求 /auth/csrf。
    Object.defineProperty(document, "cookie", {
      configurable: true,
      get: () => "XSRF-TOKEN=test-token",
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  function jsonResponse(body: unknown) {
    return { ok: true, status: 200, json: async () => body } as unknown as Response;
  }

  const fetchMock = () => vi.mocked(fetch);

  it("keeps multipart Content-Type untouched for the question image upload", async () => {
    fetchMock().mockResolvedValue(jsonResponse({
      id: "asset-1", url: "/api/v1/question-images/asset-1", originalName: "图.png",
      contentType: "image/png", byteSize: 12,
    }));

    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], "图.png", { type: "image/png" });
    await manageApi.uploadQuestionImage(file);

    expect(fetchMock()).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock().mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/manage/question-images");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("file")).toBe(file);
    // 关键契约：调用方不得手工设置 Content-Type，交给浏览器带 boundary。
    expect(new Headers(init.headers).has("Content-Type")).toBe(false);
    expect(new Headers(init.headers).get("X-XSRF-TOKEN")).toBe("test-token");
  });

  it("still sends application/json for ordinary JSON writes", async () => {
    fetchMock().mockResolvedValue(jsonResponse({ id: "question-1" }));

    await manageApi.saveQuestion({
      id: "question-1", revision: 3, stemImageId: null,
    } as Parameters<typeof manageApi.saveQuestion>[0]);

    const [, init] = fetchMock().mock.calls[0] as [string, RequestInit];
    expect(new Headers(init.headers).get("Content-Type")).toBe("application/json");
    expect(JSON.parse(String(init.body))).toMatchObject({
      stemImageId: null, expectedRevision: 3,
    });
  });

  it("submits the current stemImageId in the question payload", async () => {
    fetchMock().mockResolvedValue(jsonResponse({ id: "question-1" }));

    await manageApi.saveQuestion({
      id: "question-1", revision: 1, stemImageId: "asset-b",
    } as Parameters<typeof manageApi.saveQuestion>[0]);

    const [, init] = fetchMock().mock.calls[0] as [string, RequestInit];
    expect(JSON.parse(String(init.body))).toMatchObject({ stemImageId: "asset-b" });
  });
});

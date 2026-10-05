const SAME_ORIGIN_MESSAGE = "知境 Learner Session 要求浏览器 API 与前端同源；请将 VITE_API_BASE_URL 设为 /api/v1，并通过 Vite / 反向代理转发。";

export function resolveApiBaseUrl(configured: string | undefined, pageOrigin: string): string {
  const value = (configured?.trim() || "/api/v1").replace(/\/$/, "");
  const resolved = new URL(value || "/", pageOrigin);
  if (resolved.origin !== pageOrigin) throw new Error(SAME_ORIGIN_MESSAGE);
  return value;
}

import type { GameApi } from "../domain/types";
const baseUrl = (import.meta.env.VITE_API_BASE_URL || "/api/v1").replace(
  /\/$/,
  "",
);
async function request<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(
      typeof error?.message === "string"
        ? error.message
        : `请求失败（${response.status}）`,
    );
  }
  return response.json() as Promise<T>;
}
export const httpApi: GameApi = {
  getSession: () => request("/session"),
  submitAnswer: (input) => request("/session/answers", input),
  nextQuestion: (attemptId) => request("/session/next", { attemptId }),
  getBanks: () => request("/question-banks"),
  getMistakes: () => request("/mistakes"),
  getStatistics: () => request("/statistics"),
};

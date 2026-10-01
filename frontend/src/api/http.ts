/**
 * Java 后端预留适配层。路径、请求体和返回模型见 docs/api.md。
 * 默认不会调用此文件的 HTTP 方法；只有 VITE_API_MODE=http 时启用。
 * 不自动降级成本地数据，避免用户误以为写入了服务器。
 */
import type { GameApi } from "../domain/types";
const baseUrl = (import.meta.env.VITE_API_BASE_URL || "/api/v1").replace(
  /\/$/,
  "",
);
async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const response = await fetch(baseUrl + path, {
    method,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(error?.message || "请求失败（" + response.status + "）");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
const gamePath = (id: string) => "/games/" + encodeURIComponent(id);
export const httpApi: GameApi = {
  registerExam: (id, examId) =>
    request(gamePath(id) + "/exams/register", "POST", { examId }),
  beginActivity: (id, activityId) =>
    request(gamePath(id) + "/activities", "POST", { activityId }),
  finishActivity: (id, runId) =>
    request(gamePath(id) + "/activities/finish", "POST", { runId }),
  abandonActivity: (id, runId) =>
    request(gamePath(id) + "/activities/abandon", "POST", { runId }),
  travel: (id, locationId) =>
    request(gamePath(id) + "/travel", "POST", { locationId }),
  talk: (id, npcId, topicId) =>
    request(gamePath(id) + "/talk", "POST", { npcId, topicId }),
  dismissEncounter: (id) => request(gamePath(id) + "/encounter", "DELETE"),
  useItem: (id, itemId) =>
    request(gamePath(id) + "/items/use", "POST", { itemId }),
  buyItem: (id, itemId) =>
    request(gamePath(id) + "/items/buy", "POST", { itemId }),
  claimBond: (id, npcId, milestone) =>
    request(gamePath(id) + "/bonds", "POST", { npcId, milestone }),
  bootstrap: () => request("/bootstrap"),
  createGame: (config) => request("/games", "POST", config),
  getGame: (id) => request(gamePath(id)),
  deleteGame: (id) => request(gamePath(id), "DELETE"),
  answer: (id, input) => request(gamePath(id) + "/answers", "POST", input),
  next: (id, attemptId, reviewOnly) =>
    request(gamePath(id) + "/next", "POST", { attemptId, reviewOnly }),
  choose: (id, eventId, choiceId) =>
    request(gamePath(id) + "/choices", "POST", { eventId, choiceId }),
  saveNote: (id, questionId, note) =>
    request(gamePath(id) + "/notes", "PUT", { questionId, note }),
  configure: (id, bankIds, weights) =>
    request(gamePath(id) + "/configuration", "PUT", { bankIds, weights }),
  acknowledgeChapter: (id, chapterId) =>
    request(gamePath(id) + "/chapter", "POST", { chapterId }),
  putBank: (bank) =>
    request("/question-banks/" + encodeURIComponent(bank.id), "PUT", bank),
  deleteBank: (id) =>
    request("/question-banks/" + encodeURIComponent(id), "DELETE"),
  exportSave: (id) => request(gamePath(id) + "/export"),
  importSave: (json) => request("/games/import", "POST", { json }),
};

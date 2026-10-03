/**
 * Java 后端预留适配层。路径、请求体和返回模型见 docs/api.md。
 * 默认不会调用此文件的 HTTP 方法；只有 VITE_API_MODE=http 时启用。
 * 不自动降级成本地数据，避免用户误以为写入了服务器。
 */
import type {
  Bank,
  Bootstrap,
  Game,
  GameApi,
  Question,
  QuestionBankManifest,
} from "../domain/types";
import {
  cacheAnsweredQuestion,
  loadAnsweredQuestions,
  loadCachedBanks,
} from "./catalog-cache";
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
let cachedBanks: Bank[] = [];
const answeredQuestions = new Map<string, Question>();
async function hydrateGame(value: Game): Promise<Game> {
  const questions = new Map(
    cachedBanks
      .flatMap((bank) => bank.questions)
      .map((question) => [question.id, question]),
  );
  answeredQuestions.forEach((question, id) => questions.set(id, question));
  const attempt = value.attempt;
  if (attempt?.result) {
    const question: Question = {
      ...attempt.question,
      answer: attempt.result.standard,
      aliases: attempt.result.aliases,
      keywords: [],
      explanation: attempt.result.explanation,
    };
    questions.set(question.id, question);
    answeredQuestions.set(question.id, question);
    await cacheAnsweredQuestion(question).catch(() => undefined);
  }
  const missingIds = [
    ...new Set(
      value.records
        .map((record) => {
          const wire = record as typeof record & { questionId?: string };
          return wire.question ? "" : wire.questionId || "";
        })
        .filter((id) => id && !questions.has(id)),
    ),
  ];
  if (missingIds.length) {
    const cached = await loadAnsweredQuestions(missingIds).catch(() => []);
    cached.forEach((question) => {
      questions.set(question.id, question);
      answeredQuestions.set(question.id, question);
    });
  }
  value.records = value.records.map((record) => {
    const wire = record as typeof record & { questionId?: string };
    if (wire.question) return wire;
    const question = questions.get(wire.questionId || "");
    if (!question)
      throw new Error("本地题库缓存缺少历史题目，请重新载入题库。");
    return { ...wire, question };
  });
  return value;
}
const gameRequest = async (path: string, method = "GET", body?: unknown) =>
  await hydrateGame(await request<Game>(path, method, body));
export const httpApi: GameApi = {
  registerExam: (id, examId) =>
    gameRequest(gamePath(id) + "/exams/register", "POST", { examId }),
  beginActivity: (id, activityId) =>
    gameRequest(gamePath(id) + "/activities", "POST", { activityId }),
  finishActivity: (id, runId) =>
    gameRequest(gamePath(id) + "/activities/finish", "POST", { runId }),
  abandonActivity: (id, runId) =>
    gameRequest(gamePath(id) + "/activities/abandon", "POST", { runId }),
  travel: (id, locationId) =>
    gameRequest(gamePath(id) + "/travel", "POST", { locationId }),
  talk: (id, npcId, topicId) =>
    gameRequest(gamePath(id) + "/talk", "POST", { npcId, topicId }),
  dismissEncounter: (id) => gameRequest(gamePath(id) + "/encounter", "DELETE"),
  useItem: (id, itemId) =>
    gameRequest(gamePath(id) + "/items/use", "POST", { itemId }),
  buyItem: (id, itemId) =>
    gameRequest(gamePath(id) + "/items/buy", "POST", { itemId }),
  claimBond: (id, npcId, milestone) =>
    gameRequest(gamePath(id) + "/bonds", "POST", { npcId, milestone }),
  bootstrap: async () => {
    const data = await request<
      Omit<Bootstrap, "banks"> & {
        bankManifest: QuestionBankManifest[];
        banks?: Bank[];
      }
    >("/bootstrap");
    const banks = data.bankManifest
      ? await loadCachedBanks(data.bankManifest, (id) =>
          request<Bank>("/question-banks/" + encodeURIComponent(id)),
        )
      : data.banks || [];
    cachedBanks = banks;
    return { ...data, banks };
  },
  createGame: (config) => gameRequest("/games", "POST", config),
  getGame: (id) => gameRequest(gamePath(id)),
  deleteGame: (id) => request(gamePath(id), "DELETE"),
  answer: (id, input) => gameRequest(gamePath(id) + "/answers", "POST", input),
  reveal: (id, attemptId, questionId) =>
    gameRequest(gamePath(id) + "/answers/reveal", "POST", {
      attemptId,
      questionId,
    }),
  selfAssess: (id, attemptId, questionId, assessment) =>
    gameRequest(gamePath(id) + "/answers/self-assess", "POST", {
      attemptId,
      questionId,
      assessment,
    }),
  next: (id, attemptId, reviewOnly) =>
    gameRequest(gamePath(id) + "/next", "POST", { attemptId, reviewOnly }),
  choose: (id, eventId, choiceId) =>
    gameRequest(gamePath(id) + "/choices", "POST", { eventId, choiceId }),
  saveNote: (id, questionId, note) =>
    gameRequest(gamePath(id) + "/notes", "PUT", { questionId, note }),
  configure: (id, bankIds, weights) =>
    gameRequest(gamePath(id) + "/configuration", "PUT", { bankIds, weights }),
  acknowledgeChapter: (id, chapterId) =>
    gameRequest(gamePath(id) + "/chapter", "POST", { chapterId }),
  putBank: (bank) =>
    request("/question-banks/" + encodeURIComponent(bank.id), "PUT", bank),
  deleteBank: (id) =>
    request("/question-banks/" + encodeURIComponent(id), "DELETE"),
  exportSave: (id) => request(gamePath(id) + "/export"),
  importSave: (json) => gameRequest("/games/import", "POST", { json }),
};

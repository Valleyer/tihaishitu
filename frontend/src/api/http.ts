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
} from "./catalog-cache";
const baseUrl = (import.meta.env.VITE_API_BASE_URL || "/api/v1").replace(
  /\/$/,
  "",
);
export class HttpError extends Error {
  status: number;
  constructor(status: number, message: string) { super(message); this.status = status; }
}
const csrfCookieName = "XSRF-TOKEN";
const csrfHeaderName = "X-XSRF-TOKEN";
let csrfRequest: Promise<void> | null = null;
function csrfToken(): string | null {
  const prefix = csrfCookieName + "=";
  const value = document.cookie
    .split(";")
    .map((cookie) => cookie.trim())
    .find((cookie) => cookie.startsWith(prefix));
  return value ? decodeURIComponent(value.slice(prefix.length)) : null;
}
async function ensureCsrfToken(): Promise<string> {
  const current = csrfToken();
  if (current) return current;
  csrfRequest ||= fetch(baseUrl + "/learner/auth/csrf", {
    credentials: "include",
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(15000),
  }).then(async (response) => {
    if (!response.ok) throw new HttpError(response.status, "无法取得安全令牌。");
  }).finally(() => { csrfRequest = null; });
  await csrfRequest;
  const issued = csrfToken();
  if (!issued) throw new HttpError(0, "浏览器未保存安全令牌。");
  return issued;
}
export async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const normalizedMethod = method.toUpperCase();
  const csrf = ["POST", "PUT", "DELETE"].includes(normalizedMethod)
    ? await ensureCsrfToken()
    : null;
  const response = await fetch(baseUrl + path, {
    method: normalizedMethod,
    credentials: "include",
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(csrf ? { [csrfHeaderName]: csrf } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new HttpError(response.status, error?.message || "请求失败（" + response.status + "）");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
const gamePath = (_id: string) => "/worlds/ancient-official";
const HISTORY_RECOVERY_BATCH_SIZE = 500;
let cachedBanks: Bank[] = [];
const answeredQuestions = new Map<string, Question>();
interface HistoryQuestionResult {
  questions: {
    attemptId: string;
    questionId: string;
    question: Question;
  }[];
}
function unavailableQuestion(id: string): Question {
  return {
    id,
    subject: "历史记录",
    category: "题目恢复",
    chapter: "",
    type: "self_assessment",
    originalType: "solution",
    presentationType: "self_assessment",
    gradingMode: "self_assessment",
    question: `题目内容暂时无法恢复\n\nQuestion ID: \`${id}\``,
    options: {},
    answer: "暂时无法恢复",
    aliases: [],
    keywords: [],
    explanation: "",
    difficulty: 1,
    frequency: 0,
    tags: [],
    knowledgePointIds: [],
    enabled: false,
  };
}
async function hydrateGame(value: Game): Promise<Game> {
  const bankQuestions = new Map(
    cachedBanks
      .flatMap((bank) => bank.questions)
      .map((question) => [question.id, question]),
  );
  const attempt = value.attempt;
  if (attempt?.result) {
    const question: Question = {
      ...attempt.question,
      answer: attempt.result.standard,
      aliases: attempt.result.aliases,
      keywords: [],
      explanation: attempt.result.explanation,
    };
    answeredQuestions.set(attempt.id, question);
    await cacheAnsweredQuestion(attempt.id, question).catch(() => undefined);
  }
  const missingRecords = value.records.filter((record) => {
    const wire = record as typeof record;
    return !wire.question && !answeredQuestions.has(wire.attemptId);
  });
  const missingAttemptIds = [
    ...new Set(
      missingRecords.map((record) => record.attemptId).filter(Boolean),
    ),
  ];
  if (missingAttemptIds.length) {
    const cached = await loadAnsweredQuestions(missingAttemptIds).catch(
      () => [],
    );
    cached.forEach(({ id, question }) => {
      answeredQuestions.set(id, question);
    });
  }
  const serverMissingAttemptIds = missingAttemptIds.filter(
    (attemptId) => !answeredQuestions.has(attemptId),
  );
  if (serverMissingAttemptIds.length) {
    const batches: string[][] = [];
    for (
      let start = 0;
      start < serverMissingAttemptIds.length;
      start += HISTORY_RECOVERY_BATCH_SIZE
    ) {
      batches.push(
        serverMissingAttemptIds.slice(
          start,
          start + HISTORY_RECOVERY_BATCH_SIZE,
        ),
      );
    }
    const recovered = (
      await Promise.all(
        batches.map((attemptIds) =>
          request<HistoryQuestionResult>(
            "/learner/history/questions",
            "POST",
            { attemptIds },
          ).catch(() => ({ questions: [] })),
        ),
      )
    ).flatMap((result) => result.questions);
    await Promise.all(
      recovered.map(async (item) => {
        const record = missingRecords.find(
          (candidate) => candidate.attemptId === item.attemptId,
        ) as (typeof missingRecords)[number] & { questionId?: string };
        if (!record || record.questionId !== item.questionId) return;
        answeredQuestions.set(item.attemptId, item.question);
        await cacheAnsweredQuestion(item.attemptId, item.question).catch(
          () => undefined,
        );
      }),
    );
  }
  value.records = value.records.map((record) => {
    const wire = record as typeof record & { questionId?: string };
    if (wire.question) return wire;
    const question =
      answeredQuestions.get(wire.attemptId) ||
      bankQuestions.get(wire.questionId || "") ||
      unavailableQuestion(wire.questionId || "未知");
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
      {
        learner: { id: string; username: string; displayName: string };
        studyProfile: { selectedBookIds: string[] };
        worlds: unknown[];
        bankManifest: QuestionBankManifest[];
        questionCatalog: Bootstrap["questionCatalog"];
      }
    >("/bootstrap");
    // Learning Hub 只需要清单；题目正文按知识点/题目详情或正式发卷按需取得，
    // 不在进入知境中枢时下载整本文集。
    const banks: Bank[] = data.bankManifest.map((bank) => ({
      id: bank.id, name: bank.name, description: bank.description,
      enabled: bank.enabled, weight: bank.weight, knowledgePoints: [], questions: [],
    }));
    cachedBanks = banks;
    return { saves: [], activeId: null, legacyNotice: false, questionCatalog: data.questionCatalog, banks };
  },
  createGame: (config) => gameRequest(gamePath("") + "/initialize", "POST", {
    characterName: config.name, gender: config.gender, origin: config.origin,
  }),
  getGame: (id) => gameRequest(gamePath(id)),
  deleteGame: async () => { throw new Error("联机世界不支持删除人生进度。"); },
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
  configure: async () => { throw new Error("请在知境中枢的学习方向中调整文集。"); },
  acknowledgeChapter: (id, chapterId) =>
    gameRequest(gamePath(id) + "/chapter", "POST", { chapterId }),
  putBank: (bank) =>
    request("/question-banks/" + encodeURIComponent(bank.id), "PUT", bank),
  deleteBank: (id) =>
    request("/question-banks/" + encodeURIComponent(id), "DELETE"),
  exportSave: async () => { throw new Error("联机世界由服务器实时保存。"); },
  importSave: async () => { throw new Error("联机世界不支持导入个人存档。"); },
};

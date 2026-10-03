import type { Bank, Question, QuestionBankManifest } from "../domain/types";

const DATABASE = "tihaishitu:http-cache";
const STORE = "question-banks";
const QUESTION_STORE = "answered-questions";
const VERSION = 2;
interface CachedBank {
  id: string;
  revision: number;
  bank: Bank;
}
export interface CachedAnsweredQuestion {
  id: string;
  question: Question;
}

function database(): Promise<IDBDatabase> {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE, VERSION);
    request.onerror = () => reject(request.error);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE))
        request.result.createObjectStore(STORE, { keyPath: "id" });
      if (!request.result.objectStoreNames.contains(QUESTION_STORE))
        request.result.createObjectStore(QUESTION_STORE, { keyPath: "id" });
    };
    request.onsuccess = () => resolve(request.result);
  });
}

/**
 * 全局动态题池不再把题面重复写进每条作答记录。题目完成后才缓存已公开的
 * 完整题目，历史记录随后凭 attempt UUID 从浏览器恢复，避免重复占用网络流量。
 */
export async function cacheAnsweredQuestion(
  attemptId: string,
  question: Question,
): Promise<void> {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(QUESTION_STORE, "readwrite");
    transaction
      .objectStore(QUESTION_STORE)
      .put({ id: attemptId, question } satisfies CachedAnsweredQuestion);
    transaction.onerror = () => reject(transaction.error);
    transaction.oncomplete = () => resolve();
  }).finally(() => db.close());
}

export async function loadAnsweredQuestions(
  ids: string[],
): Promise<CachedAnsweredQuestion[]> {
  if (!ids.length) return [];
  const db = await database();
  return new Promise<CachedAnsweredQuestion[]>((resolve, reject) => {
    const transaction = db.transaction(QUESTION_STORE);
    const store = transaction.objectStore(QUESTION_STORE);
    const result: CachedAnsweredQuestion[] = [];
    ids.forEach((id) => {
      const request = store.get(id);
      request.onerror = () => reject(request.error);
      request.onsuccess = () => {
        if (request.result)
          result.push(request.result as CachedAnsweredQuestion);
      };
    });
    transaction.onerror = () => reject(transaction.error);
    transaction.oncomplete = () => resolve(result);
  }).finally(() => db.close());
}

async function read(id: string): Promise<CachedBank | undefined> {
  const db = await database();
  return new Promise<CachedBank | undefined>((resolve, reject) => {
    const request = db.transaction(STORE).objectStore(STORE).get(id);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result as CachedBank | undefined);
  }).finally(() => db.close());
}

async function write(value: CachedBank) {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(STORE, "readwrite");
    transaction.objectStore(STORE).put(value);
    transaction.onerror = () => reject(transaction.error);
    transaction.oncomplete = () => resolve();
  }).finally(() => db.close());
}

async function removeMissing(ids: Set<string>) {
  const db = await database();
  await new Promise<void>((resolve, reject) => {
    const transaction = db.transaction(STORE, "readwrite");
    const store = transaction.objectStore(STORE);
    const keys = store.getAllKeys();
    keys.onsuccess = () =>
      keys.result.forEach((key) => {
        if (!ids.has(String(key))) store.delete(key);
      });
    transaction.onerror = () => reject(transaction.error);
    transaction.oncomplete = () => resolve();
  }).finally(() => db.close());
}

/**
 * 启动只拉取很小的文集清单。只有服务端 revision 变化时才下载题目正文，
 * Markdown、选项和解析长期保存在 IndexedDB，避免每次进入游戏重复占用带宽。
 */
export async function loadCachedBanks(
  manifests: QuestionBankManifest[],
  download: (id: string) => Promise<Bank>,
): Promise<Bank[]> {
  const result: Bank[] = [];
  for (const manifest of manifests) {
    let cached: CachedBank | undefined;
    try {
      cached = await read(manifest.id);
    } catch {
      // 隐私模式可能禁用 IndexedDB；此时仍可按需联网使用。
    }
    if (cached?.revision === manifest.revision) {
      result.push(cached.bank);
      continue;
    }
    try {
      const bank = await download(manifest.id);
      result.push(bank);
      await write({ id: manifest.id, revision: manifest.revision, bank }).catch(
        () => undefined,
      );
    } catch (error) {
      // 短暂离线时允许使用旧缓存，真正没有缓存才把网络错误交给界面。
      if (!cached) throw error;
      result.push(cached.bank);
    }
  }
  removeMissing(new Set(manifests.map((item) => item.id))).catch(
    () => undefined,
  );
  return result;
}

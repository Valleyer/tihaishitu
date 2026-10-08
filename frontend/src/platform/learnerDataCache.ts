/**
 * Hub 学习者数据内存缓存（Query Cache）。
 *
 * <p>用途：消除 Hub 内导航时「同一份 learner 数据被首页 / 学习页 / 进度页 / 进度子页各请求一次」
 * 的重复网络开销，并让页面在 cache hit 时**首帧就渲染真实数据**，而不是先清空再等请求。</p>
 *
 * <p>范围与边界：</p>
 * <ul>
 *   <li>只缓存 {@code progress(days)} / {@code book(id)} / {@code recentChapter()} 以及由它们组合出的
 *       Study Core Snapshot；</li>
 *   <li>**只存在于当前 SPA 生命周期内存**：不使用 localStorage / sessionStorage，也不引入
 *       React Query / SWR / Redux 等依赖；</li>
 *   <li>不改变任何统计口径：30 / 90 天数据一律来自 {@code platformApi.progress(days)}，前端不自行拼算；</li>
 *   <li>TTL 到期不代表清空 UI：过期值仍可作为首帧 stale 数据，由调用方在后台 revalidate。</li>
 * </ul>
 */

import { platformApi, type BookDetail, type HubBootstrap, type LearnerProgress, type RecentChapter } from "./api";

/** 进度 / 最近章节 15 秒内视为新鲜；文集详情 60 秒。 */
export const PROGRESS_TTL_MS = 15_000;
export const RECENT_TTL_MS = 15_000;
export const BOOK_TTL_MS = 60_000;

export type ProgressDays = 7 | 30 | 90;

/**
 * `platformApi.progress()` 与 `platformApi.progress(7)` 在当前契约下语义相同
 * （metrics / summary / books / recent 都不随窗口变化，`activity.daily` 默认就是 7 天），
 * 因此必须规范成同一个 cache key，避免存两份重复数据。
 */
export function normalizeProgressDays(days?: ProgressDays): ProgressDays {
  return days === undefined ? 7 : days;
}

/**
 * Learner 缓存作用域：区分用户、学习范围 revision 与所选文集。
 *
 * <p>任一变化都必须让旧缓存不再命中，避免跨用户或跨学习范围串数据。</p>
 */
export function learnerScopeKey(data: HubBootstrap): string {
  return [
    data.learner.id,
    data.studyProfile.revision,
    [...data.studyProfile.selectedBookIds].sort().join(","),
  ].join(":");
}

interface CacheEntry<T> {
  value?: T;
  loadedAt?: number;
  inFlight?: Promise<T>;
}

/** Study Core：顶部主卡片 + 学习状态所需的最小完整快照。 */
export interface StudyCoreSnapshot {
  details: BookDetail[];
  progress: LearnerProgress | null;
  recent: RecentChapter | null;
  loadedAt: number;
}

const progressEntries = new Map<string, CacheEntry<LearnerProgress>>();
const bookEntries = new Map<string, CacheEntry<BookDetail>>();
const recentEntries = new Map<string, CacheEntry<RecentChapter>>();
const studyCoreEntries = new Map<string, CacheEntry<StudyCoreSnapshot>>();

/**
 * 全局代数：每次 invalidate 自增。
 *
 * <p>请求发出时记录当时代数，resolve 时若代数已变（说明期间发生过 invalidate），
 * 则把值返回给调用方但**不写入缓存**，避免旧 in-flight 把过期数据「复活」。</p>
 */
let generation = 0;

const progressKey = (data: HubBootstrap, days?: ProgressDays) =>
  `${learnerScopeKey(data)}:progress:${normalizeProgressDays(days)}`;
const bookKey = (data: HubBootstrap, bookId: string) => `${learnerScopeKey(data)}:book:${bookId}`;
const recentKey = (data: HubBootstrap) => `${learnerScopeKey(data)}:recentChapter`;
const studyCoreKey = (data: HubBootstrap) => `${learnerScopeKey(data)}:studyCore`;

const isFresh = (loadedAt: number | undefined, ttl: number) =>
  loadedAt !== undefined && Date.now() - loadedAt < ttl;

/** 通用的「缓存优先 + in-flight dedupe + 代数守卫」读取。 */
function readThrough<T>(
  entries: Map<string, CacheEntry<T>>,
  key: string,
  ttl: number,
  fetcher: () => Promise<T>,
  force: boolean,
): Promise<T> {
  const entry = entries.get(key) ?? {};
  entries.set(key, entry);

  if (!force && entry.value !== undefined && isFresh(entry.loadedAt, ttl)) {
    return Promise.resolve(entry.value);
  }
  if (entry.inFlight) {
    return entry.inFlight;
  }

  const requestGeneration = generation;
  const request = fetcher().then(
    value => {
      // 期间发生过 invalidate：值可以返回给本次调用者，但不得写回缓存。
      if (requestGeneration === generation) {
        entry.value = value;
        entry.loadedAt = Date.now();
      }
      if (entry.inFlight === request) {
        entry.inFlight = undefined;
      }
      return value;
    },
    reason => {
      if (entry.inFlight === request) {
        entry.inFlight = undefined;
      }
      throw reason;
    },
  );
  entry.inFlight = request;
  return request;
}

function peek<T>(entries: Map<string, CacheEntry<T>>, key: string): T | undefined {
  return entries.get(key)?.value;
}

// ── Progress ─────────────────────────────────────────────────────────────────

export function peekProgress(data: HubBootstrap, days?: ProgressDays): LearnerProgress | undefined {
  return peek(progressEntries, progressKey(data, days));
}

export function loadProgress(
  data: HubBootstrap,
  days?: ProgressDays,
  options: { force?: boolean } = {},
): Promise<LearnerProgress> {
  const normalized = normalizeProgressDays(days);
  return readThrough(
    progressEntries,
    progressKey(data, normalized),
    PROGRESS_TTL_MS,
    () => platformApi.progress(normalized),
    options.force === true,
  );
}

/** 预取：只负责发起请求，调用方不应 await，也不应依赖它的成功与否。 */
export function prefetchProgress(data: HubBootstrap, days?: ProgressDays): void {
  void loadProgress(data, days).catch(() => undefined);
}

// ── Book ─────────────────────────────────────────────────────────────────────

export function peekBook(data: HubBootstrap, bookId: string): BookDetail | undefined {
  return peek(bookEntries, bookKey(data, bookId));
}

export function loadBook(
  data: HubBootstrap,
  bookId: string,
  options: { force?: boolean } = {},
): Promise<BookDetail> {
  return readThrough(
    bookEntries,
    bookKey(data, bookId),
    BOOK_TTL_MS,
    () => platformApi.book(bookId),
    options.force === true,
  );
}

export function prefetchBook(data: HubBootstrap, bookId: string): void {
  void loadBook(data, bookId).catch(() => undefined);
}

// ── Recent chapter ───────────────────────────────────────────────────────────

export function peekRecentChapter(data: HubBootstrap): RecentChapter | undefined {
  return peek(recentEntries, recentKey(data));
}

export function loadRecentChapter(
  data: HubBootstrap,
  options: { force?: boolean } = {},
): Promise<RecentChapter> {
  return readThrough(
    recentEntries,
    recentKey(data),
    RECENT_TTL_MS,
    () => platformApi.recentChapter(),
    options.force === true,
  );
}

export function prefetchRecentChapter(data: HubBootstrap): void {
  void loadRecentChapter(data).catch(() => undefined);
}

// ── Study Core Snapshot ──────────────────────────────────────────────────────

export function peekStudyCore(data: HubBootstrap): StudyCoreSnapshot | undefined {
  return peek(studyCoreEntries, studyCoreKey(data));
}

/** 三份数据就绪后组合成快照；只有三者都记录过 loadedAt 才算「完整」可缓存。 */
export function loadStudyCore(
  data: HubBootstrap,
  options: { force?: boolean } = {},
): Promise<StudyCoreSnapshot> {
  const key = studyCoreKey(data);
  const entry = studyCoreEntries.get(key) ?? {};
  studyCoreEntries.set(key, entry);
  const force = options.force === true;

  if (!force && entry.value !== undefined && isFresh(entry.value.loadedAt, PROGRESS_TTL_MS)) {
    return Promise.resolve(entry.value);
  }
  if (entry.inFlight) {
    return entry.inFlight;
  }

  const requestGeneration = generation;
  const details = Promise.all(
    data.studyProfile.selectedBookIds.map(id => loadBook(data, id, { force })),
  );
  const progress = loadProgress(data, 7, { force }).catch(() => null);
  const recent = loadRecentChapter(data, { force }).catch(() => null);

  const request = Promise.all([details, progress, recent]).then(
    ([detailValues, progressValue, recentValue]): StudyCoreSnapshot => {
      const snapshot: StudyCoreSnapshot = {
        details: detailValues,
        progress: progressValue,
        recent: recentValue,
        loadedAt: Date.now(),
      };
      // 只有在「本次请求未跨越 invalidate」且 progress / recent 都真的拿到时，
      // 这份组合快照才允许缓存，否则会把过期或缺数据的组合固化下来。
      if (requestGeneration === generation && progressValue !== null && recentValue !== null) {
        entry.value = snapshot;
        entry.loadedAt = snapshot.loadedAt;
      }
      if (entry.inFlight === request) {
        entry.inFlight = undefined;
      }
      return snapshot;
    },
    reason => {
      if (entry.inFlight === request) {
        entry.inFlight = undefined;
      }
      throw reason;
    },
  );
  entry.inFlight = request;
  return request;
}

export function prefetchStudyCore(data: HubBootstrap): void {
  void loadStudyCore(data).catch(() => undefined);
}

// ── Prefetch scheduling ──────────────────────────────────────────────────────

/** 空闲调度：优先 requestIdleCallback，缺失时退化为 setTimeout(0)；两种情况都不阻塞当前页面。 */
function runWhenIdle(task: () => void): void {
  const idleHost = window as Window & {
    requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number;
  };
  if (typeof idleHost.requestIdleCallback === "function") {
    idleHost.requestIdleCallback(task, { timeout: 1000 });
  } else {
    window.setTimeout(task, 0);
  }
}

/**
 * Bootstrap 成功后的第一次预热：只预热 Study Core（内部含 progress 7）与 progress 7。
 *
 * <p>不能 await；也**不预热 30 / 90**，避免每次登录都多打两个聚合请求。</p>
 */
export function scheduleHubPrefetch(data: HubBootstrap): void {
  runWhenIdle(() => {
    prefetchStudyCore(data);
    prefetchProgress(data, 7);
  });
}

/** 已排过 30 / 90 预热的 scope，避免组件重渲染时反复排 idle 任务。 */
const progressWindowScheduledScopes = new Set<string>();

/** 进度页 7 天稳定后预热 30 → 90；同一 scope 只排一次，且幂等不会重复发请求。 */
export function scheduleProgressWindowPrefetch(data: HubBootstrap): void {
  const scope = learnerScopeKey(data);
  if (progressWindowScheduledScopes.has(scope)) return;
  progressWindowScheduledScopes.add(scope);
  runWhenIdle(() => {
    prefetchProgress(data, 30);
    prefetchProgress(data, 90);
  });
}

// ── Invalidation ─────────────────────────────────────────────────────────────

/** 提升代数，使此前发出的 in-flight 结果无法再写回缓存。 */
function bumpGeneration(): void {
  generation += 1;
}

/** 练习类 mutation 后失效：progress 全窗口 / recent / Study Core；book 详情不受影响。 */
export function invalidateLearnerDynamicData(): void {
  bumpGeneration();
  progressEntries.clear();
  recentEntries.clear();
  studyCoreEntries.clear();
  // 失效后允许重新预热目标窗口。
  progressWindowScheduledScopes.clear();
}

/** 学习范围变更 / 登出后失效：连 book 详情一起清，防止跨 scope 或跨用户串数据。 */
export function invalidateLearnerAllData(): void {
  bumpGeneration();
  progressEntries.clear();
  bookEntries.clear();
  recentEntries.clear();
  studyCoreEntries.clear();
  progressWindowScheduledScopes.clear();
}

/** 仅测试使用：清空所有缓存并重置代数。 */
export function resetLearnerDataCache(): void {
  bumpGeneration();
  progressEntries.clear();
  bookEntries.clear();
  recentEntries.clear();
  studyCoreEntries.clear();
  progressWindowScheduledScopes.clear();
}

/** 仅测试使用：当前代数，用于断言 invalidate 后旧 in-flight 没有回写。 */
export function currentCacheGeneration(): number {
  return generation;
}

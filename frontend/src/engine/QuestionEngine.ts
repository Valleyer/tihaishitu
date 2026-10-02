/**
 * 选题顺序：有效题库 → 启用科目 → 间隔过滤 → 到期复习 → 科目加权 → 题目加权。
 * 科目权重为零会彻底排除；题库太小时放宽间隔，否则会无题可做。
 * 这里只决定抽哪题；选项洗牌在发卷时进行，不能改变题目原始答案。
 */
import type { Bank, Game, KnowledgePoint, Question } from "../domain/types";
import {
  hydrateBankKnowledge,
  knowledgePointKey,
  questionKey,
} from "./QuestionBankManager";
import { gameDesign } from "../content";
import { isLearningMastered } from "./SpacedRepetitionEngine";
export function eligibleQuestions(
  game: Game,
  banks: Bank[],
  includeMastered = false,
): Question[] {
  return banks
    .filter(
      (b) => b.enabled && b.weight > 0 && game.config.bankIds.includes(b.id),
    )
    .flatMap((bank) => {
      const hydrated = hydrateBankKnowledge(bank);
      return hydrated.questions
        .filter((q) => {
          const id = questionKey(bank.id, q.id);
          return (
            ["single_choice", "multiple_choice", "true_false"].includes(
              q.type,
            ) &&
            q.enabled &&
            (game.config.weights[q.subject] ?? 1) > 0 &&
            (includeMastered || !isLearningMastered(game.learning[id]))
          );
        })
        .map((q) => ({
          ...q,
          id: questionKey(bank.id, q.id),
          knowledgePointIds: q.knowledgePointIds.map((id) =>
            knowledgePointKey(hydrated.id, id),
          ),
        }));
    });
}

export function resolveKnowledgePoints(
  banks: Bank[],
  ids: string[],
): KnowledgePoint[] {
  return ids.flatMap((key) => {
    const [bankId, pointId] = key.split("::");
    const point = banks
      .find((bank) => bank.id === bankId)
      ?.knowledgePoints.find((candidate) => candidate.id === pointId);
    return point ? [{ ...point, id: key }] : [];
  });
}

/** 从可用题目反推知识点池，确保一轮内不会用同一知识点重复占分。 */
export function planKnowledgePoints(
  game: Game,
  banks: Bank[],
  count: number,
  reviewOnly = false,
  random = Math.random,
): string[] {
  let questions = eligibleQuestions(game, banks);
  if (reviewOnly)
    questions = questions.filter((question) =>
      game.records.some(
        (record) =>
          !record.correct && record.question.id === question.id,
      ),
    );
  const ids = [...new Set(questions.flatMap((q) => q.knowledgePointIds))];
  if (ids.length < count)
    throw new Error(
      `当前启用文集只有 ${ids.length} 个可用知识点，本次活动需要 ${count} 个。请在藏书阁补充或启用知识点。`,
    );
  // Fisher–Yates 只改变本轮顺序，不修改题库原数组。
  for (let index = ids.length - 1; index > 0; index--) {
    const target = Math.floor(random() * (index + 1));
    [ids[index], ids[target]] = [ids[target], ids[index]];
  }
  return ids.slice(0, count);
}

/**
 * 知识点首题兼顾既有复习权重；诊断训练优先未出现、难度更低的同知识点题。
 * 当题库暂时只有一道同类题时允许重做，避免行程被数据规模卡死。
 */
export function drawQuestionForKnowledgePoint(
  game: Game,
  banks: Bank[],
  pointId: string,
  training: boolean,
  seen: string[],
  random = Math.random,
): Question {
  const all = eligibleQuestions(game, banks, true).filter((question) =>
    question.knowledgePointIds.includes(pointId),
  );
  if (!all.length) throw new Error("当前知识点没有可用题目，请检查藏书阁配置。");
  const unseen = all.filter((question) => !seen.includes(question.id));
  const pool = unseen.length ? unseen : all;
  if (training) {
    const minDifficulty = Math.min(...pool.map((question) => question.difficulty));
    const easier = pool.filter(
      (question) => question.difficulty === minDifficulty,
    );
    return easier[Math.floor(random() * easier.length)];
  }
  return pick(
    pool,
    (question) =>
      [0.6, 0.8, 1, 1.4, 2][question.frequency - 1] *
      (banks.find((bank) => question.id.startsWith(bank.id + "::"))?.weight || 1),
    random,
  );
}
function pick<T>(
  items: T[],
  weight: (item: T) => number,
  random: () => number,
): T {
  const weights = items.map(weight);
  let value = random() * weights.reduce((a, b) => a + b, 0);
  for (let i = 0; i < items.length; i++) {
    value -= weights[i];
    if (value < 0) return items[i];
  }
  return items[items.length - 1];
}
export function drawQuestion(
  game: Game,
  banks: Bank[],
  reviewOnly = false,
  random = Math.random,
): Question {
  const all = eligibleQuestions(game, banks);
  if (!all.length) {
    if (eligibleQuestions(game, banks, true).length)
      throw new Error(
        "当前文集中的题目均已达到掌握标准，请在藏书阁启用新的文集。",
      );
    throw new Error(
      "没有可用题目。请在藏书阁启用至少一个含有效题目的文集，并确认抽取权重大于零。",
    );
  }
  const total = game.records.length;
  let pool = reviewOnly
    ? all.filter((q) => (game.learning[q.id]?.wrong ?? 0) > 0)
    : all;
  if (!pool.length) throw new Error("当前启用的题库没有旧案可重审。");
  const previous = game.attempt?.question.id;
  const fresh = pool.filter((q) => {
    const record = game.learning[q.id];
    return (
      !record ||
      total - record.lastIndex >=
        (record.wrong > 0
          ? gameDesign.review.wrongGap
          : gameDesign.review.normalGap)
    );
  });
  pool = fresh.length ? fresh : pool.filter((q) => q.id !== previous);
  if (!pool.length)
    pool = all.filter((q) =>
      reviewOnly ? (game.learning[q.id]?.wrong ?? 0) > 0 : true,
    );
  const due = pool.filter((q) => {
    const r = game.learning[q.id];
    return r?.wrong > 0 && r.dueAt <= total;
  });
  // 到期错题按配置概率优先；常规抽题先选科目，再选题，避免题库容量改变科目比例。
  if (due.length && (reviewOnly || random() < gameDesign.review.duePriority))
    return pick(due, (q) => 1 + game.learning[q.id].wrong, random);
  const subjects = [...new Set(pool.map((q) => q.subject))];
  const subject = pick(subjects, (s) => game.config.weights[s] ?? 1, random);
  const records = game.records.filter((r) => r.question.subject === subject);
  const chapters = new Map<string, number>();
  for (const r of records)
    if (!r.correct)
      chapters.set(
        r.question.chapter,
        (chapters.get(r.question.chapter) || 0) + 1,
      );
  return pick(
    pool.filter((q) => q.subject === subject),
    (q) => {
      const record = game.learning[q.id];
      const bank = banks.find((b) => q.id.startsWith(b.id + "::"));
      const frequency = [0.6, 0.8, 1, 1.4, 2][q.frequency - 1];
      const wrong = record ? Math.min(5, 1 + record.wrong) : 1;
      const retentionWeight = !record
        ? 1
        : record.streak >= 5
          ? 0.3
          : record.streak >= 3
            ? 0.6
            : record.streak >= 2
              ? 0.8
              : 1;
      const forgotten = record
        ? Math.min(
            3,
            1 + (Date.now() - Date.parse(record.lastAt)) / 86400000 / 7,
          )
        : 1.4;
      const weakness = 1 + Math.min(1, (chapters.get(q.chapter) || 0) / 10);
      return (
        frequency *
        wrong *
        retentionWeight *
        forgotten *
        weakness *
        (bank?.weight ?? 1)
      );
    },
    random,
  );
}

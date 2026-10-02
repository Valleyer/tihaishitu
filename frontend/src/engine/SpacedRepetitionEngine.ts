/**
 * 以完成题数记录复习间隔，不使用游戏内日历。
 * 初错与再错采用配置中的随机区间；连续答对扩大间隔。
 * dueAt 是达到该累计题数后进入优先队列，不保证恰好在该题数再次出现。
 */
import type { Answer, Game, Learning, Question } from "../domain/types";
import { gameDesign } from "../content";

/** 错误率按百分数保存，保留两位小数，方便界面和未来后端直接使用。 */
export function learningErrorRate(
  record: Pick<Learning, "attempts" | "wrong">,
): number {
  return record.attempts
    ? Math.round((record.wrong / record.attempts) * 10000) / 100
    : 0;
}

/**
 * 达到任一配置档位即视为掌握，不再参与普通抽题和旧案重审。
 * 规则放在 content/game.json，策划可增删档位或调整容错次数。
 */
export function isLearningMastered(
  record: Pick<Learning, "attempts" | "wrong"> | undefined,
): boolean {
  return Boolean(
    record &&
      gameDesign.review.masteryRules.some(
        (rule) =>
          record.attempts >= rule.attempts && record.wrong <= rule.maxWrong,
      ),
  );
}

/** 兼容旧存档：补算后来新增的统计字段，不清除任何历史数据。 */
export function hydrateLearning(game: Game): void {
  for (const record of Object.values(game.learning))
    record.errorRate = learningErrorRate(record);
}

export function recordLearning(
  game: Game,
  question: Question,
  correct: boolean,
  answer: Answer,
  now: string,
  random = Math.random,
) {
  const old = game.learning[question.id];
  const record: Learning = old || {
    attempts: 0,
    correct: 0,
    wrong: 0,
    errorRate: 0,
    streak: 0,
    lastIndex: 0,
    dueAt: 0,
    lastAt: now,
    wrongAnswers: [],
    reviewCount: 0,
  };
  record.attempts++;
  record.lastAt = now;
  record.lastIndex = game.records.length;
  if (old?.wrong) record.reviewCount++;
  if (correct) {
    record.correct++;
    record.streak++;
    record.dueAt =
      game.records.length +
      Math.min(
        gameDesign.review.masteredMaxGap,
        gameDesign.review.masteredBaseGap * 2 ** record.streak,
      );
  } else {
    record.wrong++;
    record.streak = 0;
    record.wrongAnswers.push(answer);
    const settings = gameDesign.review;
    const min =
      record.wrong === 1 ? settings.firstWrongMin : settings.repeatWrongMin;
    const max =
      record.wrong === 1 ? settings.firstWrongMax : settings.repeatWrongMax;
    record.dueAt =
      game.records.length + min + Math.floor(random() * (max - min + 1));
  }
  record.errorRate = learningErrorRate(record);
  game.learning[question.id] = record;
}

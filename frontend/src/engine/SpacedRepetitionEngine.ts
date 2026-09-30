/**
 * 以完成题数记录复习间隔，不使用游戏内日历。
 * 初错与再错采用配置中的随机区间；连续答对扩大间隔。
 * dueAt 是达到该累计题数后进入优先队列，不保证恰好在该题数再次出现。
 */
import type { Answer, Game, Learning, Question } from "../domain/types";
import { gameDesign } from "../content";
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
  game.learning[question.id] = record;
}

/**
 * 从实际作答记录汇总统计，按本机自然日统计今日与连续天数。
 * 游戏里的第几日由剧情时钟控制，与现实学习日期分开。
 */
import type { Game } from "../domain/types";
const dateKey = (date: Date) =>
  [date.getFullYear(), date.getMonth() + 1, date.getDate()].join("-");
export function statistics(game: Game) {
  const days = Array.from({ length: 7 }, (_, i) => {
    const date = new Date();
    date.setDate(date.getDate() - 6 + i);
    return {
      key: dateKey(date),
      label: date.getMonth() + 1 + "/" + date.getDate(),
      count: 0,
    };
  });
  const subjects: Record<string, { total: number; correct: number }> = {};
  const chapters: Record<string, { total: number; correct: number }> = {};
  const dates = new Set<string>();
  for (const record of game.records) {
    const key = dateKey(new Date(record.at));
    dates.add(key);
    const day = days.find((d) => d.key === key);
    if (day) day.count++;
    for (const [group, name] of [
      [subjects, record.question.subject],
      [chapters, record.question.subject + " · " + record.question.chapter],
    ] as const) {
      group[name] ||= { total: 0, correct: 0 };
      group[name].total++;
      group[name].correct += Number(record.correct);
    }
  }
  let consecutive = 0;
  const date = new Date();
  if (!dates.has(dateKey(date))) date.setDate(date.getDate() - 1);
  while (dates.has(dateKey(date))) {
    consecutive++;
    date.setDate(date.getDate() - 1);
  }
  const correct = game.records.filter((r) => r.correct).length;
  return {
    total: game.records.length,
    correct,
    today: days[6].count,
    accuracy: game.records.length
      ? Math.round((100 * correct) / game.records.length)
      : 0,
    consecutive,
    days,
    subjects,
    chapters,
  };
}

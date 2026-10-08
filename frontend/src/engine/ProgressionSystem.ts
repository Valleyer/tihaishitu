/**
 * Legacy local 路径只保留章节转换。单题不再增加学识或声望；
 * 四项核心成长一律由整轮 Activity / Exam 结算。
 */
import type { Change, Game } from "../domain/types";
import { chapters } from "./StoryEngine";
export function settleProgress(
  game: Game,
  _correct: boolean,
  _difficulty: number,
  _frequency: number,
): Change[] {
  // 普通读书不自动结识场景人物，关系只由实际拜访与共读推进。
  const total = game.records.length;
  const next = chapters[game.chapter + 1];
  const favorability = game.npcs.reduce((sum, n) => sum + n.favorability, 0);
  if (
    next &&
    total >= next.threshold &&
    game.player.knowledge >= next.knowledge &&
    favorability >= next.favorability
  ) {
    game.chapter++;
    game.player.title = next.playerTitle;
    game.journal.push({
      id: crypto.randomUUID(),
      day: total,
      kind: "milestone",
      title: "新篇 · " + next.title,
      text: "你的课业积累与周围人的认可，使你走到了新的路口。" + next.goal,
    });
  }
  return [];
}

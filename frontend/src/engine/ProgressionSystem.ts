/**
 * 每次正式判题只结算一次成长。答错仍可积累基础学识，不直接终止人生。
 * 晋章同时要求累计课业、学识、全体人物信任总和达标，不能仅靠题数跳级。
 * 此版止于县试备考；称号来自章节配置，尚未实现正式科举与升官。
 */
import type { Change, Game } from "../domain/types";
import { chapters } from "./StoryEngine";
import { gameDesign } from "../content";
export function settleProgress(
  game: Game,
  correct: boolean,
  difficulty: number,
  frequency: number,
): Change[] {
  const scene = game.attempt!.scene;
  const npc = game.npcs.find((n) => n.id === scene.npcId)!;
  npc.met = true;
  const changes: Change[] = [];
  const increase = (label: string, before: number, after: number) => {
    if (before !== after) changes.push({ label, before, after });
  };
  const oldKnowledge = game.player.knowledge;
  const growth = gameDesign.growth;
  const gain = correct
    ? Math.min(
        growth.maxGain,
        growth.correctBase +
          Math.ceil(difficulty / growth.difficultyDivisor) +
          (frequency >= growth.highFrequencyThreshold
            ? growth.highFrequencyBonus
            : 0),
      )
    : growth.wrongGain;
  game.player.knowledge += gain;
  increase("学识", oldKnowledge, game.player.knowledge);
  if (correct) {
    const oldTrust = npc.trust;
    npc.trust = Math.min(growth.relationshipMax, npc.trust + growth.trustGain);
    increase(npc.name + " · 信任", oldTrust, npc.trust);
  }
  const total = game.records.length;
  if (total % growth.reputationEvery === 0 && correct) {
    const before = game.player.reputation;
    game.player.reputation++;
    increase("声望", before, game.player.reputation);
  }
  const next = chapters[game.chapter + 1];
  const trust = game.npcs.reduce((sum, n) => sum + n.trust, 0);
  if (
    next &&
    total >= next.threshold &&
    game.player.knowledge >= next.knowledge &&
    trust >= next.trust
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
  return changes;
}

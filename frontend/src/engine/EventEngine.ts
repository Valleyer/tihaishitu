/**
 * 际遇由题数门槛触发，以 event:<id> 标记已完成状态。
 * 选项后果只读取 effects；hint 是展示文案，不参与数值计算，两者应同步修改。
 */
import type { Game } from "../domain/types";
import { eventDesign, gameDesign } from "../content";
export function pendingEvent(game: Game) {
  return (
    eventDesign.find(
      (event) =>
        game.records.length >= event.at &&
        !game.flags.includes("event:" + event.id),
    ) ?? null
  );
}
export function applyChoice(game: Game, eventId: string, choiceId: string) {
  if (!game.event || game.event.id !== eventId) {
    if (game.flags.includes("event:" + eventId)) return;
    throw new Error("这段际遇已经变化，请重新打开存档。");
  }
  const event = eventDesign.find((event) => event.id === eventId);
  const option = event?.options.find((option) => option.id === choiceId);
  if (!event || !option) throw new Error("请选择有效的回应。");
  const effects = option.effects;
  for (const key of ["knowledge", "coins", "reputation"] as const)
    game.player[key] = Math.max(0, game.player[key] + (effects[key] || 0));
  for (const key of ["trust", "affinity"] as const)
    for (const [id, gain] of Object.entries(effects[key] || {})) {
      const npc = game.npcs.find((npc) => npc.id === id);
      if (npc) {
        npc.met = true;
        npc[key] = Math.min(
          gameDesign.growth.relationshipMax,
          Math.max(0, npc[key] + gain),
        );
      }
    }
  game.flags.push(...(effects.flags || []), "event:" + eventId);
  game.journal.push({
    id: crypto.randomUUID(),
    day: game.records.length,
    title: event.title,
    text: option.text + "。" + option.hint + "。",
    kind: "choice",
  });
  game.event = null;
}

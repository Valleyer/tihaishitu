/**
 * 剧情组按章节配置定位，再按本章累计课业选择场景。
 * 初次使用作者写好的对白；用完后进入日常续读，避免每几题重新演一遍初次见面。
 * 已有共同经历与错题复习可替换对白；{player}、{npc} 在出场时替换。
 */
import type { Game, Question, Scene } from "../domain/types";
import {
  characterDesign,
  chapterDesign,
  gameDesign,
  sceneDesign,
  locationFor,
} from "../content";
export const initialNpcs = () => structuredClone(characterDesign);
export const chapters = chapterDesign;
export function calendar(game: Game) {
  const settings = gameDesign.calendar;
  const day = Math.floor(game.records.length / settings[game.config.pace]);
  const seasonDays = settings.daysPerYear / settings.seasons.length;
  return {
    day: day + 1,
    label:
      gameDesign.era +
      (gameDesign.startYear + Math.floor(day / settings.daysPerYear)) +
      "年 · " +
      settings.seasons[Math.floor(day / seasonDays) % settings.seasons.length] +
      " · 第" +
      (Math.floor(day % seasonDays) + 1) +
      "日",
    time: settings.times[
      Math.floor(game.records.length / 2) % settings.times.length
    ],
  };
}
export function makeScene(
  game: Game,
  question: Question,
  review: boolean,
): Scene {
  const chapter = chapters[game.chapter];
  const group = sceneDesign.groups.find(
    (group) => group.id === chapter.sceneGroup,
  )!;
  const offset = Math.max(0, game.records.length - chapter.threshold);
  const row = group.scenes[offset % group.scenes.length];
  const repeated = offset >= group.scenes.length;
  const npc = game.npcs.find((npc) => npc.id === row.npcId)!;
  const substitute = (text: string) =>
    text.replaceAll("{npc}", npc.name).replaceAll("{player}", game.player.name);
  const tasks: Record<string, string> = gameDesign.subjectTasks;
  const memory = sceneDesign.memories.find(
    (memory) => memory.npcId === npc.id && game.flags.includes(memory.flag),
  );
  const dialogue = review
    ? sceneDesign.reviewDialogue
    : memory
      ? memory.dialogue
      : repeated
        ? sceneDesign.repeatDialogues[
            offset % sceneDesign.repeatDialogues.length
          ]
        : row.dialogue;
  return {
    id: row.id + ":" + game.records.length,
    title:
      (review ? "旧案重审 · " : "") + row.title + (repeated ? " · 续" : ""),
    location: locationFor(game.chapter).name,
    speaker: npc.name,
    role: npc.role,
    npcId: npc.id,
    text: substitute(
      review
        ? sceneDesign.reviewContext
        : repeated
          ? sceneDesign.repeatContext
          : row.context,
    ),
    dialogue: substitute(dialogue),
    task: tasks[question.subject] || gameDesign.defaultTask,
    success: substitute(repeated ? sceneDesign.repeatSuccess : row.success),
    failure: substitute(repeated ? sceneDesign.repeatFailure : row.failure),
  };
}

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
/**
 * 顶部纪年直接取玩家本机时间：公历年份减 2000 作为景和年号，
 * 月日使用中文写法，小时按传统十二时辰（每个时辰两小时）换算。
 */
export function calendar(now = new Date()) {
  const digits = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九"],
    chineseNumber = (value: number) => {
      if (value < 10) return digits[value];
      if (value === 10) return "十";
      if (value < 20) return "十" + digits[value - 10];
      return (
        digits[Math.floor(value / 10)] +
        "十" +
        (value % 10 ? digits[value % 10] : "")
      );
    },
    earthlyBranches = [
      "子",
      "丑",
      "寅",
      "卯",
      "辰",
      "巳",
      "午",
      "未",
      "申",
      "酉",
      "戌",
      "亥",
    ],
    branchIndex = Math.floor(((now.getHours() + 1) % 24) / 2);
  return {
    label:
      gameDesign.era +
      (now.getFullYear() - 2000) +
      "年" +
      chineseNumber(now.getMonth() + 1) +
      "月" +
      chineseNumber(now.getDate()) +
      "日",
    time: earthlyBranches[branchIndex] + "时",
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

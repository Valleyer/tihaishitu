/**
 * 可配置内容的统一入口。JSON 不能写注释，字段说明与示例见 docs/configuration-guide.md。
 * id 是关联键，修改文字不必改 id；修改章节顺序或删除人物后建议新开存档。
 * 内容只在这里导入；界面与规则引擎统一引用，避免多处维护同一套设定。
 */
import gameData from "./game.json";
import chapterData from "./chapters.json";
import characterData from "./characters.json";
import sceneData from "./scenes.json";
import eventData from "./events.json";
import mapData from "./maps.json";
import portraitData from "./portraits.json";
import bankData from "./question-banks.json";
import activityData from "./activities.json";
import companionData from "./companions.json";
import itemData from "./items.json";
import adventureData from "./adventure.json";
import examData from "./exams.json";
import type {
  Activity,
  Companion,
  Exam,
  Item,
  Rewards,
  WorldLocation,
} from "../domain/adventure";

/**
 * 普通活动统一为五题两档；科举正试统一为十题全对取中。
 * 配置中旧有的 80 分首次奖励合并到完美档，避免升级后丢失专属物品。
 * 0 分档只保留失败对白供结算使用，界面不会把它展示成奖励档。
 */
function mergeRewards(values: (Rewards | undefined)[]): Rewards | undefined {
  const merged: Rewards = {};
  for (const reward of values) {
    if (!reward) continue;
    for (const key of ["knowledge", "coins", "reputation"] as const)
      if (reward[key]) merged[key] = (merged[key] || 0) + reward[key]!;
    for (const key of ["attributes", "affinity", "trust", "items"] as const)
      for (const [id, amount] of Object.entries(reward[key] || {})) {
        merged[key] ??= {};
        merged[key]![id] = (merged[key]![id] || 0) + amount;
      }
    if (reward.flags)
      merged.flags = [...new Set([...(merged.flags || []), ...reward.flags])];
    if (reward.title) merged.title = reward.title;
  }
  return Object.keys(merged).length ? merged : undefined;
}
function normalizeActivity(activity: Activity): Activity {
  // 所有玩法共用这里的全局答题规格。内容作者只需在 adventure.json 改一次，
  // 人物共读、副本、支线与后续科举主线便会保持一致。
  const rules = adventureData.answerRules;
  const tiers = [...activity.tiers].sort((a, b) => a.minScore - b.minScore),
    zero = tiers.find((tier) => tier.minScore === 0) || tiers[0],
    positive = tiers.filter((tier) => tier.minScore > 0),
    base = tiers.find((tier) => tier.minScore === 60) || positive[0] || zero,
    perfect = tiers.find((tier) => tier.minScore === 100) || positive.at(-1) || base,
    perfectFirst = mergeRewards(
      tiers.filter((tier) => tier.minScore > 60).map((tier) => tier.firstRewards),
    ),
    mainFirst = mergeRewards(
      // 主线现在只有“全对”一个成功档，旧及格档中的身份、道具和开放标记也要一并迁入。
      tiers.filter((tier) => tier.minScore > 0).map((tier) => tier.firstRewards),
    );
  if (activity.kind === "exam" || activity.quest === "main")
    return {
      ...activity,
      quest: "main",
      rounds: rules.mainRounds,
      passScore: rules.mainPassScore,
      tiers: [
        {
          ...zero,
          minScore: 0,
          label: "未取中",
          rewards: {},
          firstRewards: undefined,
        },
        {
          ...perfect,
          minScore: rules.mainPassScore,
          label: activity.kind === "exam" ? "全对取中" : "全对完成",
          firstRewards: mainFirst,
        },
      ],
    };
  return {
    ...activity,
    rounds: rules.ordinaryRounds,
    passScore: rules.ordinaryPassScore,
    tiers: [
      {
        ...zero,
        minScore: 0,
        label: "未过关",
        rewards: {},
        firstRewards: undefined,
      },
      {
        ...base,
        minScore: rules.ordinaryPassScore,
        label: "基础过关",
      },
      {
        ...perfect,
        minScore: rules.perfectScore,
        label: "完美过关",
        firstRewards: perfectFirst,
      },
    ],
  };
}
export const activities = (activityData as unknown as Activity[]).map(
  normalizeActivity,
);
export const companions = companionData as unknown as Companion[];
export const items = itemData as unknown as Item[];
export const adventureDesign = adventureData;
export const exams = examData as unknown as Exam[];
import type { Bank, ChoiceEvent, Npc } from "../domain/types";
export const gameDesign = gameData;
export const chapterDesign = chapterData;
export const characterDesign = characterData as (Npc & { portrait: string })[];
export const sceneDesign = sceneData;
export interface EventEffects {
  knowledge?: number;
  coins?: number;
  reputation?: number;
  trust?: Record<string, number>;
  affinity?: Record<string, number>;
  flags?: string[];
}
export const eventDesign = eventData as (Omit<ChoiceEvent, "options"> & {
  at: number;
  speaker: string;
  npcId: string;
  options: (ChoiceEvent["options"][number] & { effects: EventEffects })[];
})[];
export const mapDesign = mapData as unknown as { locations: WorldLocation[] };
export const portraitDesign = portraitData;
export const bankDesign = bankData as unknown as Bank[];
export const locationFor = (chapter: number) =>
  mapDesign.locations.find(
    (location) => location.id === chapterDesign[chapter].locationId,
  ) || mapDesign.locations[0];

/** Config boundary: all legacy relationship and activity shapes become canonical here. */
import gameData from "./game.json";
import chapterData from "./chapters.json";
import characterData from "./characters.json";
import sceneData from "./scenes.json";
import eventData from "./events.json";
import mapData from "./maps.json";
import portraitData from "./portraits.json";
import bankData from "./question-banks.json";
import activityData from "./activities.json";
import activityV7Data from "./activities-v7.json";
import activityV8Data from "./activities-v8.json";
import companionData from "./companions.json";
import companionV8Data from "./companions-v8.json";
import itemData from "./items.json";
import itemV7Data from "./items-v7.json";
import itemV8Data from "./items-v8.json";
import adventureData from "./adventure.json";
import examData from "./exams.json";
import type {
  Activity,
  Companion,
  Exam,
  Item,
  MapRegion,
  Requirements,
  Rewards,
  WorldLocation,
} from "../domain/adventure";
import type { Bank, ChoiceEvent, Npc } from "../domain/types";
import { hydrateBankKnowledge } from "../engine/QuestionBankManager";

type LegacyRewards = Rewards & {
  affinity?: Record<string, number>;
  trust?: Record<string, number>;
};
type LegacyRequirements = Requirements & {
  affinity?: Record<string, number>;
  trust?: Record<string, number>;
};

function normalizeReward(reward?: LegacyRewards): Rewards {
  if (!reward) return {};
  const { affinity, trust, ...current } = reward;
  const favorability = { ...(current.favorability || {}) };
  for (const source of [affinity, trust])
    for (const [id, amount] of Object.entries(source || {}))
      favorability[id] = Math.max(favorability[id] || 0, amount);
  return { ...current, ...(Object.keys(favorability).length ? { favorability } : {}) };
}

function normalizeRequirements(requirements: LegacyRequirements = {}): Requirements {
  const { affinity, trust, ...current } = requirements;
  const favorability = { ...(current.favorability || {}) };
  for (const source of [affinity, trust])
    for (const [id, amount] of Object.entries(source || {}))
      favorability[id] = Math.max(favorability[id] || 0, amount);
  return { ...current, ...(Object.keys(favorability).length ? { favorability } : {}) };
}

/** Same-key legacy rewards use maxima so old tiers cannot stack into an inflated task payout. */
function mergeRewards(values: (LegacyRewards | undefined)[]): Rewards {
  const merged: Rewards = {};
  for (const source of values) {
    const reward = normalizeReward(source);
    for (const key of ["knowledge", "coins", "reputation"] as const)
      if (reward[key] !== undefined)
        merged[key] = Math.max(merged[key] || 0, reward[key]!);
    for (const key of ["attributes", "favorability", "items"] as const)
      for (const [id, amount] of Object.entries(reward[key] || {})) {
        merged[key] ??= {};
        merged[key]![id] = Math.max(merged[key]![id] || 0, amount);
      }
    if (reward.flags)
      merged.flags = [...new Set([...(merged.flags || []), ...reward.flags])];
    if (reward.title) merged.title = reward.title;
  }
  return merged;
}

function normalizeActivity(activity: Activity): Activity {
  const rules = adventureData.answerRules;
  const tiers = [...activity.tiers].sort((a, b) => a.minScore - b.minScore);
  const zero = tiers.find((tier) => tier.minScore === 0) || tiers[0];
  const positive = tiers.filter((tier) => tier.minScore > 0);
  const base = tiers.find((tier) => tier.minScore === 60) || positive[0] || zero;
  const perfect = tiers.find((tier) => tier.minScore === 100) || positive.at(-1) || base;
  const main = activity.kind === "exam" || activity.quest === "main";
  const task =
    activity.activityMode === "task" ||
    (activity.activityMode !== "repeatable" &&
      !activity.repeatable &&
      (main || activity.quest === "side" || ["dungeon", "story"].includes(activity.kind)));
  const common = {
    ...activity,
    activityMode: task ? ("task" as const) : ("repeatable" as const),
    repeatable: !task,
    requirements: normalizeRequirements(activity.requirements as LegacyRequirements),
  };
  if (task) {
    const passScore = main ? rules.mainPassScore : rules.ordinaryPassScore;
    const completionReward = mergeRewards(
      positive.flatMap((tier) => [
        tier.rewards as LegacyRewards,
        tier.firstRewards as LegacyRewards | undefined,
      ]),
    );
    return {
      ...common,
      ...(main ? { quest: "main" as const } : {}),
      rounds: main ? rules.mainRounds : rules.ordinaryRounds,
      passScore,
      completionReward,
      successDialogue: perfect.dialogue,
      failureDialogue: zero.dialogue,
      tiers: [
        { ...zero, minScore: 0, label: main ? "尚未完成" : "继续努力", rewards: {}, firstRewards: undefined },
        {
          ...perfect,
          minScore: passScore,
          label: main ? (activity.kind === "exam" ? "全对取中" : "全对完成") : "任务完成",
          rewards: completionReward,
          firstRewards: undefined,
        },
      ],
    };
  }
  return {
    ...common,
    rounds: rules.ordinaryRounds,
    passScore: rules.ordinaryPassScore,
    tiers: [
      { ...zero, minScore: 0, label: "继续修习", rewards: {}, firstRewards: undefined },
      {
        ...base,
        minScore: rules.ordinaryPassScore,
        label: "通关奖励",
        rewards: normalizeReward(base.rewards as LegacyRewards),
        firstRewards: base.firstRewards
          ? normalizeReward(base.firstRewards as LegacyRewards)
          : undefined,
      },
    ],
  };
}

const normalizeCompanion = (companion: Companion): Companion => ({
  ...companion,
  greetings: companion.greetings.map((entry) => ({
    text: entry.text,
    minFavorability: entry.minFavorability ?? (entry as unknown as { minAffinity: number }).minAffinity ?? 0,
  })),
  topics: companion.topics.map((entry) => ({
    id: entry.id,
    label: entry.label,
    lines: entry.lines,
    minFavorability: entry.minFavorability ?? (entry as unknown as { minAffinity: number }).minAffinity ?? 0,
  })),
  milestones: companion.milestones.map((entry) => ({
    ...entry,
    favorability: entry.favorability ?? (entry as unknown as { affinity: number }).affinity ?? 0,
    reward: normalizeReward(entry.reward as LegacyRewards),
  })),
});

export const activities = [
  ...(activityData as unknown as Activity[]),
  ...(activityV7Data as unknown as Activity[]),
  ...(activityV8Data as unknown as Activity[]),
].map(normalizeActivity);
export const companions = [
  ...(companionData as unknown as Companion[]),
  ...(companionV8Data as unknown as Companion[]),
].map(normalizeCompanion);
export const items = [
  ...(itemData as unknown as Item[]),
  ...(itemV7Data as unknown as Item[]),
  ...(itemV8Data as unknown as Item[]),
].map((item) => ({ ...item, use: item.use ? normalizeReward(item.use as LegacyRewards) : undefined }));
export const adventureDesign = adventureData;
export const exams = (examData as unknown as Exam[]).map((exam) => ({
  ...exam,
  requirements: normalizeRequirements(exam.requirements as LegacyRequirements),
}));
export const gameDesign = gameData;
export const chapterDesign = chapterData;
export const characterDesign = (characterData as unknown as (Npc & {
  portrait: string;
  affinity?: number;
  trust?: number;
})[]).map(({ affinity, trust, ...npc }) => ({
  ...npc,
  favorability: Math.max(npc.favorability || 0, affinity || 0, trust || 0),
}));
export const sceneDesign = sceneData;
export interface EventEffects extends Rewards {}
export const eventDesign = (eventData as unknown as (Omit<ChoiceEvent, "options"> & {
  at: number;
  speaker: string;
  npcId: string;
  options: (ChoiceEvent["options"][number] & { effects: LegacyRewards })[];
})[]).map((event) => ({
  ...event,
  options: event.options.map((option) => ({ ...option, effects: normalizeReward(option.effects) })),
}));
const rawMap = mapData as unknown as { regions: MapRegion[]; locations: WorldLocation[] };
export const mapDesign = {
  regions: rawMap.regions.map((region) => ({ ...region, requirements: normalizeRequirements(region.requirements as LegacyRequirements) })),
  locations: rawMap.locations.map((location) => ({ ...location, requirements: normalizeRequirements(location.requirements as LegacyRequirements) })),
};
export const portraitDesign = portraitData;
export const bankDesign = (bankData as unknown as Bank[]).map(hydrateBankKnowledge);
export const locationFor = (chapter: number) =>
  mapDesign.locations.find((location) => location.id === chapterDesign[chapter].locationId) || mapDesign.locations[0];
export const favorabilityLevel = (value: number) =>
  adventureData.favorabilityLevels.find((level) => value >= level.min && value <= level.max)?.name || "初识";

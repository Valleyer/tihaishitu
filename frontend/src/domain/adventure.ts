/** V2—V6 的探索契约。所有奖励、门槛和轮数由配置定义，存档记录实际进度。 */
export interface Requirements {
  knowledge?: number;
  reputation?: number;
  attributes?: Record<string, number>;
  affinity?: Record<string, number>;
  items?: string[];
  flags?: string[];
}
export interface WorldLocation {
  id: string;
  name: string;
  description: string;
  background: string;
  position: string;
  x: number;
  y: number;
  requirements: Requirements;
  npcs: string[];
  ambience: string;
}
export interface Rewards {
  knowledge?: number;
  coins?: number;
  reputation?: number;
  attributes?: Record<string, number>;
  affinity?: Record<string, number>;
  trust?: Record<string, number>;
  items?: Record<string, number>;
  flags?: string[];
  /** 身份称号属于一次性进阶奖励，例如县试取中。 */
  title?: string;
}
export interface RewardTier {
  minScore: number;
  label: string;
  rewards: Rewards;
  firstRewards?: Rewards;
  dialogue: string;
}
export interface Activity {
  id: string;
  kind: "study" | "companion" | "dungeon" | "story" | "exam";
  name: string;
  subtitle: string;
  description: string;
  invitation: string;
  npcId?: string;
  locationId?: string;
  rounds: number;
  passScore: number;
  repeatable: boolean;
  requirements: Requirements;
  tiers: RewardTier[];
  reviewOnly?: boolean;
}
export type ExamStatus = "unregistered" | "registered" | "preparing" | "passed";
export interface ExamRecord {
  status: ExamStatus;
  attempts: number;
  best: number;
  lastScore: number;
}
/**
 * 科举本身也走活动答题，但报名、落榜与重考由独立状态机管理。
 * 以后增加府试、院试，只需继续追加 exams.json 配置和活动，无需改存档结构。
 */
export interface Exam {
  id: string;
  name: string;
  subtitle: string;
  description: string;
  locationId: string;
  activityId: string;
  preparationActivityId: string;
  fee: number;
  requirements: Requirements;
  dialogues: {
    unregistered: string;
    registered: string;
    preparing: string;
    passed: string;
  };
}
export interface ActivityRun {
  id: string;
  definition: Activity;
  answered: number;
  correct: number;
  status: "active" | "settled";
  score: number;
  grade: string;
  rewards: string[];
  response: string;
}
export interface Adventure {
  version: 6;
  locationId: string;
  visited: string[];
  attributes: Record<string, number>;
  inventory: Record<string, number>;
  equipped: Record<string, string>;
  best: Record<string, number>;
  clears: Record<string, number>;
  rewardClaims: string[];
  conversations: Record<string, number>;
  seenEncounters: string[];
  encounter: string | null;
  run: ActivityRun | null;
  exams: Record<string, ExamRecord>;
}
export interface Item {
  id: string;
  name: string;
  description: string;
  kind: "equipment" | "consumable" | "keepsake";
  rarity: string;
  symbol: string;
  slot?: string;
  bonuses?: Record<string, number>;
  use?: Rewards;
  price?: number;
}
export interface Companion {
  npcId: string;
  locationId: string;
  personality: string;
  greetings: { minAffinity: number; text: string }[];
  topics: { id: string; label: string; minAffinity: number; lines: string[] }[];
  activities: string[];
  milestones: {
    affinity: number;
    title: string;
    reward: Rewards;
    dialogue: string;
  }[];
}

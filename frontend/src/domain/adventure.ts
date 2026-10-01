/** V2—V5 的探索契约。所有奖励、门槛和轮数由配置定义，存档记录实际进度。 */
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
  kind: "study" | "companion" | "dungeon" | "story";
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
  version: 5;
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

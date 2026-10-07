/** V2—V6 的探索契约。所有奖励、门槛和轮数由配置定义，存档记录实际进度。 */
export interface Requirements {
  knowledge?: number;
  reputation?: number;
  attributes?: Record<string, number>;
  favorability?: Record<string, number>;
  items?: string[];
  flags?: string[];
}
export interface WorldLocation {
  id: string;
  /** 所属大地图；界面始终显示“大地图名 · 小地点名”。 */
  regionId: string;
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
export interface MapRegion {
  id: string;
  name: string;
  description: string;
  background: string;
  position: string;
  requirements: Requirements;
}
export interface Rewards {
  knowledge?: number;
  coins?: number;
  reputation?: number;
  attributes?: Record<string, number>;
  favorability?: Record<string, number>;
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
  /** 主支线标签只影响世界中的任务提示；科举规则仍由 exam 类型决定。 */
  quest?: "main" | "side";
  name: string;
  subtitle: string;
  description: string;
  invitation: string;
  npcId?: string;
  locationId?: string;
  rounds: number;
  passScore: number;
  repeatable: boolean;
  activityMode: "task" | "repeatable";
  requirements: Requirements;
  tiers: RewardTier[];
  completionReward?: Rewards;
  successDialogue?: string;
  failureDialogue?: string;
  entryCost?: number;
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
  /** 首次答对的知识点数；训练题答对只推进进度，不重复计分。 */
  correct: number;
  /** 本轮抽取的不同知识点，数量等于普通 5 / 主线 10。 */
  knowledgePointIds: string[];
  /** 本轮已完成 / 已作答的正式题数：correct / wrong / partial 都推进一个 slot。 */
  knowledgePointIndex: number;
  /**
   * 本轮计划完成的正式题数（= min(活动 rounds, 当天剩余可出的随机题数)）。
   * 进度分母必须用它，不能在题目不足时仍然按 activity.rounds 显示。
   */
  plannedRounds?: number;
  /** Legacy /games/** 兼容字段：现代 Learner World 不再进入 training 分支。 */
  training: boolean;
  trainingAnswered: number;
  /** 前置核验与目标复核题数；不计入本轮得分。 */
  diagnosticAnswered: number;
  /** 正式联机 World 只保存诊断会话指针，诊断事实保存在服务端数据表。 */
  diagnosisSessionId?: string | null;
  seenQuestionIds: string[];
  status: "active" | "settled";
  score: number;
  grade: string;
  rewards: string[];
  response: string;
  entryCost: number;
  costCommitted: boolean;
  costRefunded: boolean;
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
  greetings: { minFavorability: number; text: string }[];
  topics: { id: string; label: string; minFavorability: number; lines: string[] }[];
  activities: string[];
  milestones: {
    favorability: number;
    title: string;
    reward: Rewards;
    dialogue: string;
  }[];
}

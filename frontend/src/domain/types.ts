/**
 * 前后端共用的数据契约。Java 接口后续应按这里的字段返回 JSON。
 * Question 保留完整标准答案；PublicQuestion 只暴露作答前可见内容。
 * answer 使用题库原始选项键（或判断题布尔值），不要提交屏幕上随机后的字母编号。
 * aliases、keywords、selfAssessment 为早期数据兼容字段，新三题型不依赖自然语言自评。
 */
export type QuestionType = "single_choice" | "multiple_choice" | "true_false";
export type Answer = string | string[] | boolean;
export interface Question {
  id: string;
  subject: string;
  category: string;
  chapter: string;
  type: QuestionType;
  question: string;
  options: Record<string, string>;
  answer: Answer;
  aliases: string[];
  keywords: string[];
  explanation: string;
  difficulty: number;
  frequency: number;
  tags: string[];
  enabled: boolean;
}
export type PublicQuestion = Omit<
  Question,
  "answer" | "aliases" | "keywords" | "explanation"
>;
export interface Bank {
  id: string;
  name: string;
  description: string;
  questions: Question[];
  enabled: boolean;
  weight: number;
}
export interface NewGame {
  name: string;
  gender: string;
  origin: string;
  bankIds: string[];
  weights: Record<string, number>;
  pace: "slow" | "normal";
  difficulty: "gentle" | "standard";
}
export interface Npc {
  id: string;
  name: string;
  role: string;
  description: string;
  affinity: number;
  trust: number;
  met: boolean;
}
export interface Player {
  name: string;
  gender: string;
  origin: string;
  knowledge: number;
  reputation: number;
  coins: number;
  title: string;
}
export interface Change {
  label: string;
  before: number;
  after: number;
}
export interface Scene {
  id: string;
  title: string;
  location: string;
  speaker: string;
  role: string;
  text: string;
  dialogue: string;
  task: string;
  npcId: string;
  success: string;
  failure: string;
}
export interface Result {
  correct: boolean | null;
  answer: Answer;
  standard: Answer;
  explanation: string;
  aliases: string[];
  story: string;
  changes: Change[];
}
export interface Attempt {
  id: string;
  question: PublicQuestion;
  scene: Scene;
  result: Result | null;
  review: boolean;
}
export interface StudyRecord {
  attemptId: string;
  question: Question;
  answer: Answer;
  correct: boolean;
  at: string;
  review: boolean;
}
export interface Learning {
  attempts: number;
  correct: number;
  wrong: number;
  streak: number;
  lastIndex: number;
  dueAt: number;
  lastAt: string;
  wrongAnswers: Answer[];
  reviewCount: number;
}
export interface JournalEntry {
  id: string;
  day: number;
  title: string;
  text: string;
  kind: "story" | "choice" | "milestone";
}
export interface ChoiceEvent {
  id: string;
  title: string;
  text: string;
  options: { id: string; text: string; hint: string }[];
}
export interface Game {
  /** 旧存档首次读取时迁移；新存档始终包含探索进度。 */
  adventure?: import("./adventure").Adventure;
  id: string;
  version: 2;
  createdAt: string;
  updatedAt: string;
  config: NewGame;
  player: Player;
  npcs: Npc[];
  records: StudyRecord[];
  learning: Record<string, Learning>;
  journal: JournalEntry[];
  flags: string[];
  attempt: Attempt | null;
  event: ChoiceEvent | null;
  notes: Record<string, string>;
  chapter: number;
  revision: number;
}
export interface SaveSummary {
  id: string;
  name: string;
  title: string;
  total: number;
  updatedAt: string;
}
export interface Bootstrap {
  saves: SaveSummary[];
  banks: Bank[];
  activeId: string | null;
  legacyNotice: boolean;
}
export interface AnswerInput {
  attemptId: string;
  answer: Answer;
  selfAssessment?: boolean;
}
export interface GameApi {
  /** 报名只登记资格并扣费，不会立刻发卷。 */
  registerExam(id: string, examId: string): Promise<Game>;
  beginActivity(id: string, activityId: string): Promise<Game>;
  finishActivity(id: string, runId: string): Promise<Game>;
  abandonActivity(id: string, runId: string): Promise<Game>;
  travel(id: string, locationId: string): Promise<Game>;
  talk(id: string, npcId: string, topicId: string): Promise<Game>;
  dismissEncounter(id: string): Promise<Game>;
  useItem(id: string, itemId: string): Promise<Game>;
  buyItem(id: string, itemId: string): Promise<Game>;
  claimBond(id: string, npcId: string, milestone: number): Promise<Game>;
  bootstrap(): Promise<Bootstrap>;
  createGame(config: NewGame): Promise<Game>;
  getGame(id: string): Promise<Game>;
  deleteGame(id: string): Promise<void>;
  answer(id: string, input: AnswerInput): Promise<Game>;
  next(id: string, attemptId: string, reviewOnly?: boolean): Promise<Game>;
  choose(id: string, eventId: string, choiceId: string): Promise<Game>;
  saveNote(id: string, questionId: string, note: string): Promise<Game>;
  configure(
    id: string,
    bankIds: string[],
    weights: Record<string, number>,
  ): Promise<Game>;
  acknowledgeChapter(id: string, chapterId: string): Promise<Game>;
  putBank(bank: Bank): Promise<Bank>;
  deleteBank(id: string): Promise<void>;
  exportSave(id: string): Promise<string>;
  importSave(json: string): Promise<Game>;
}

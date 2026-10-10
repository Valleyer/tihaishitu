/**
 * 前后端共用的数据契约。Java 接口后续应按这里的字段返回 JSON。
 * Question.answer 是后端 runtime-derived grading value：正式题由
 * question_resource_option.correct_option 在发题时派生并 remap，
 * 不是 Question 持久化层保存的标准答案配置。
 * 正式题唯一答案事实 = 客观题 option.correct；综合题 = analysis_markdown。
 * PublicQuestion 只暴露作答前可见内容。
 * answer 使用题库原始选项键（或判断题布尔值），不要提交屏幕上随机后的字母编号。
 * aliases、keywords、selfAssessment 为早期数据兼容字段，新三题型不依赖自然语言自评。
 */
export type QuestionType =
  | "single_choice"
  | "multiple_choice"
  | "true_false"
  | "self_assessment";
export type OriginalQuestionType =
  | "single_choice"
  | "multiple_choice"
  | "true_false"
  | "solution";
export type Assessment = "correct" | "partial" | "wrong";
export type Answer = string | string[] | boolean;
/**
 * 知识点是比章节更细的教学单位。一道题关联 1–3 个知识点；名称应具体到
 * “无条件极值的极值点判定”这类可以单独诊断、单独训练的能力。
 */
export interface KnowledgePoint {
  id: string;
  name: string;
  subject: string;
  category: string;
  description: string;
  /** 支持 Markdown + LaTeX，用于题面上的“查看知识点解析”。 */
  explanation: string;
  parentId?: string;
  prerequisites: string[];
  tags: string[];
}
export interface Question {
  id: string;
  subject: string;
  category: string;
  chapter: string;
  type: QuestionType;
  /** 原卷题型、游戏展示与判题方式彼此独立；旧题库缺省时沿用 type/auto。 */
  originalType?: OriginalQuestionType;
  presentationType?: QuestionType;
  gradingMode?: "auto" | "self_assessment";
  /** Markdown + LaTeX；后端按原文返回，前端负责安全渲染。 */
  question: string;
  stemImageUrl?: string | null;
  /** Formal Question revision frozen into the attempt snapshot; old snapshots may omit it. */
  revision?: number;
  options: Record<string, string>;
  answer: Answer;
  aliases: string[];
  keywords: string[];
  explanation: string;
  difficulty: number;
  frequency: number;
  tags: string[];
  knowledgePointIds: string[];
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
  knowledgePoints: KnowledgePoint[];
  questions: Question[];
  enabled: boolean;
  weight: number;
}
export interface QuestionBankManifest {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  weight: number;
  revision: number;
  questionCount: number;
  knowledgePointCount: number;
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
  favorability: number;
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
  /**
   * 只对客观题返回的 Attempt 级判题值（服务器内部冻结的 derived standard）。
   * 综合题只有一份 analysis_markdown，不再返回独立 standard。
   */
  standard?: Answer;
  explanation: string;
  aliases: string[];
  story: string;
  changes: Change[];
  assessment?: Assessment;
  gradingSource?: "automatic" | "self";
  noIdea?: boolean;
}
/** 综合题 reveal 只返回一份完整解析，不再有独立参考答案。 */
export interface RevealedAnswer {
  explanation: string;
  knowledgePoints: KnowledgePoint[];
}
/**
 * 题面上的知识点标签。role 只表达主次：core 为主标签，auxiliary 为辅助标签；
 * 两种角色都会展示，也都不再决定题目能不能做。
 */
export interface ExamKnowledgePointTag {
  id: string;
  name: string;
  role: string;
}
/**
 * 正式题面的来源 metadata，由后端在发题时冻结进 attempt snapshot，
 * 刷新同一个 attempt 不会变化。Hub Practice 与 World / 副本共用同一结构。
 * UI 只使用 displayQuestionNumber 生成“第 N 题”，questionNumber 是数据库原始值。
 */
export interface ExamMetadata {
  subjectName?: string | null;
  sourceName?: string | null;
  examYear?: number | null;
  questionNumber?: string | null;
  displayQuestionNumber?: string | null;
  examLabel?: string | null;
  knowledgePoints?: ExamKnowledgePointTag[];
}
export interface Attempt {
  id: string;
  targetKnowledgePointId?: string;
  targetKnowledgePointName?: string;
  learningPurpose?: "查根问底" | "补基础" | "回卷再试" | "温故补缺" | null;
  question: PublicQuestion & { knowledgePoints: KnowledgePoint[]; examMetadata?: ExamMetadata };
  scene: Scene;
  result: Result | null;
  reveal?: RevealedAnswer | null;
  review: boolean;
}
export interface StudyRecord {
  attemptId: string;
  question: Question;
  answer: Answer;
  correct: boolean;
  at: string;
  review: boolean;
  assessment?: Assessment;
  gradingSource?: "automatic" | "self";
}
export interface Learning {
  /** 累计作答次数、答对数与答错数用于判断该题是否已经掌握。 */
  attempts: number;
  correct: number;
  wrong: number;
  partial?: number;
  /** 0–100 的累计错误率；旧存档读取时会自动补算。 */
  errorRate?: number;
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
  /** 上线后由后端声明为 server/readonly，普通用户不再各自维护题库。 */
  questionCatalog: {
    source: "local" | "server";
    canEdit: boolean;
    revision?: string;
  };
  activeId: string | null;
  legacyNotice: boolean;
}
export interface AnswerInput {
  attemptId: string;
  /** 只传 UUID；服务端从答题快照取标准答案，不接收题目 Markdown。 */
  questionId?: string;
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
  noIdea(id: string, attemptId: string, questionId: string): Promise<Game>;
  reveal(id: string, attemptId: string, questionId: string): Promise<Game>;
  selfAssess(
    id: string,
    attemptId: string,
    questionId: string,
    assessment: Assessment,
  ): Promise<Game>;
  reportQuestion(attemptId: string, reason: string, comment: string): Promise<void>;
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

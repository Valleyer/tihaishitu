import { request } from "../api/http";

export interface Learner { id: string; username: string; displayName: string; revision: number }
export interface KnowledgePoint {
  id: string; code: string; name: string; subject: string; section: string; chapter: string;
  description: string; explanation: string; role?: string;
}
export interface StudyProfile {
  pace: "slow" | "normal"; difficulty: "gentle" | "standard"; focusMode: "auto" | "manual";
  revision: number; selectedBookIds: string[]; weights: Record<string, number>;
  focusedKnowledgePointIds: string[]; focusedKnowledgePoints: KnowledgePoint[];
}
export interface WorldDefinition { id: string; name: string; description: string; enabled: boolean; initialized: boolean; updatedAt?: string; entryPath?: string }
export interface BookSummary { id: string; name: string; description: string; revision: number; knowledgePointCount: number; totalKnowledgePointCount: number; questionCount: number }
export interface Chapter {
  id: string; code: string; name: string; description: string; sortOrder: number;
  knowledgePointCount: number; trainableKnowledgePointCount: number; publishedQuestionCount: number;
  availableKnowledgePointCount?: number;
  knowledgePoints: KnowledgePoint[];
}
export interface BookDetail extends BookSummary { chapters: Chapter[] }
export interface BrowseQuestion {
  id: string; subject: string; sourceType: string; sourceName?: string; examYear?: number; questionNumber?: string; questionType: string;
  presentationType: string; gradingMode: string; contentMarkdown: string; analysisMarkdown: string;
  standardAnswer: unknown; difficulty: number; revision: number;
  options?: { key: string; text: string }[]; knowledgePoints?: KnowledgePoint[];
  learnerQuestionStatus?: "unseen" | "mastered" | "needs_review";
  latestAssessment?: "correct" | "partial" | "wrong"; lastGradedAt?: string;
}
export interface HubBootstrap {
  learner: Learner; canManage: boolean; studyProfile: StudyProfile; worlds: WorldDefinition[];
  bankManifest: BookSummary[]; questionCatalog: { source: string; canEdit: boolean; revision?: string };
}
export interface KnowledgeState {
  knowledgePointId: string; masteryScore: number; effectiveMastery: number; stabilityDays: number;
  band: "unstarted" | "unmastered" | "learning" | "ready" | "proficient"; ready: boolean;
  targetDifficulty: number; evidenceCount: number; correctStreak: number; wrongStreak: number;
  lastOutcome?: string; lastEvidenceAt?: string; lastCorrectAt?: string; modelVersion: string; revision: number;
}
export interface ReviewQueueItem {
  knowledgePointId: string; name: string; subject: string; section: string; chapter: string;
  effectiveMastery: number; stabilityDays: number; targetDifficulty: number;
  lastEvidenceAt: string; reviewDueAt: string; status: "due" | "soon" | "upcoming"; playable: boolean;
}
export interface ReviewQueue {
  generatedAt: string;
  summary: { due: number; soon: number; upcoming: number; playableDueOrSoon: number };
  items: ReviewQueueItem[];
}
export type MasteryBand = "unstarted" | "unmastered" | "learning" | "ready" | "proficient";
export interface ProgressChapter {
  chapterId: string; code: string; name: string; total: number; started: number;
  ready: number; proficient: number; masteryProgress: number;
}
export interface ProgressBook {
  bookId: string; name: string; description: string; totalKnowledgePoints: number;
  started: number; ready: number; proficient: number; masteryProgress: number; reviewDueOrSoon: number;
  chapters: ProgressChapter[];
}
export interface LearnerProgress {
  generatedAt: string;
  summary: {
    selectedBooks: number; totalKnowledgePoints: number; startedKnowledgePoints: number;
    readyKnowledgePoints: number; proficientKnowledgePoints: number; reviewDue: number;
    reviewSoon: number; reviewUpcoming: number; wrongQuestions: number;
  };
  bands: Record<MasteryBand, number>;
  books: ProgressBook[];
  recent: {
    gradedAttempts7d: number; distinctKnowledgePoints7d: number; activeStudyDays7d: number;
    daily: { date: string; gradedAttempts: number; distinctKnowledgePoints: number }[];
    knowledgePoints: {
      knowledgePointId: string; name: string; bookName: string; chapterName: string;
      band: MasteryBand; effectiveMastery: number; stabilityDays: number; lastEvidenceAt: string;
    }[];
  };
}
export interface WrongQuestion {
  questionId: string; targetKnowledgePointId: string; knowledgePointName: string;
  contentMarkdown: string; lastGradedAt: string; available: boolean;
  unavailableReason?: "out_of_scope" | "question_unavailable" | "knowledge_unavailable" | null;
  /** 动态生成的真题展示标签，例如 2022年考研数学一真题；后端按 exam_year + subject_name 生成。 */
  examLabel?: string | null; sourceName?: string | null; examYear?: number | null;
  /** questionNumber 是数据库原始题号；displayQuestionNumber 是 UI 用的已格式化题号。 */
  questionNumber?: string | null; displayQuestionNumber?: string | null; subjectName?: string | null;
  /** 全部知识点标签（core / auxiliary 都返回，role 只表达主次）。 */
  knowledgePoints?: KnowledgePoint[];
}
export interface PracticeKnowledgePointTag { id: string; name: string; role: string }
export interface PracticeAttempt {
  id: string; status: "active" | "revealed" | "graded"; targetKnowledgePointId: string;
  targetKnowledgePointName: string; evidenceMode: "normal" | "training" | "remedial"; diagnosisRole?: string;
  question: {
    id: string; subject: string; chapter: string; presentationType: string; gradingMode: string;
    question: string; options: Record<string, string>; difficulty: number;
  };
  standard?: unknown; explanation?: string; assessment?: "correct" | "partial" | "wrong";
  gradingSource?: string; answerRevealed: boolean;
  /** 题面来源信息：来源名 + 真题标签 + 题号，发题时冻结进 question_snapshot_json。 */
  sourceName?: string | null; examYear?: number | null;
  /** questionNumber 是数据库原始题号；displayQuestionNumber 才是 UI 使用的题号。 */
  questionNumber?: string | null; displayQuestionNumber?: string | null;
  examLabel?: string | null; knowledgePoints?: PracticeKnowledgePointTag[];
}
export type PracticeIntent = "knowledge_drill" | "wrong_review" | "wrong_drill" | "chapter_drill";
export interface PracticeSession {
  id: string; intent: PracticeIntent; targetKnowledgePointId?: string;
  targetBookId?: string; targetChapterId?: string; currentKnowledgePointId?: string;
  sourceQuestionId?: string; status: "active" | "ended"; revision: number;
  currentAttempt: PracticeAttempt; flowComplete: boolean; canRepeat: boolean;
}
/**
 * Study 页“最近章节”快捷入口。
 * status=active 恢复 activeSessionId；status=last 用相同 Book + Chapter 新建 Session；status=none 表示从未练过。
 */
export interface RecentChapter {
  status: "active" | "last" | "none"; activeSessionId?: string | null; lastSessionId?: string | null;
  bookId?: string | null; bookName?: string | null; chapterId?: string | null; chapterName?: string | null;
  currentKnowledgePointId?: string | null; currentKnowledgePointIndex?: number | null;
  knowledgePointCount?: number | null; updatedAt?: string | null;
}
export interface KnowledgeDirectoryItem extends KnowledgePoint {
  bookId: string; bookName: string; chapterId: string; catalogChapter: string;
  publishedQuestionCount: number;
}
export interface KnowledgeDirectoryFacets { subjects: string[] }
export interface PageResult<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export interface LearnerStatistics {
  days: 7 | 30 | 90; generatedAt: string;
  summary: {
    gradedAttempts: number; activeStudyDays: number; distinctKnowledgePoints: number;
    knowledgeDrillAttempts: number; wrongReviewAttempts: number; worldAttempts: number;
    correct: number; partial: number; wrong: number;
  };
  daily: { date: string; gradedAttempts: number; distinctKnowledgePoints: number }[];
  books: { bookId: string; name: string; masteryProgress: number; knowledgePointCount: number }[];
}
export interface KnowledgeGuide { knowledgePointId: string; contentMarkdown?: string; revision: number; updatedAt?: string }
export interface KnowledgeNeighbor { id: string; name: string; chapterId: string; chapterName: string }
export interface KnowledgeNeighbors { previous?: KnowledgeNeighbor; next?: KnowledgeNeighbor }

export const platformApi = {
  register: (username: string, displayName: string, password: string) =>
    request<Learner>("/learner/auth/register", "POST", { username, displayName, password }),
  login: (username: string, password: string) =>
    request<Learner>("/learner/auth/login", "POST", { username, password }),
  logout: () => request<void>("/learner/auth/logout", "POST"),
  me: () => request<Learner>("/learner/me"),
  bootstrap: () => request<HubBootstrap>("/bootstrap"),
  books: () => request<BookSummary[]>("/learning/books"),
  book: (id: string) => request<BookDetail>("/learning/books/" + encodeURIComponent(id)),
  knowledge: (id: string) => request<KnowledgePoint & { books: { id: string; name: string; chapterId: string; chapterName: string }[] }>(
    "/learning/knowledge-points/" + encodeURIComponent(id)),
  knowledgeDirectory: (filters: { query?: string; bookId?: string; chapterId?: string; subject?: string; page?: number; size?: number }) => {
    const params = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => {
      if (value !== undefined && value !== "") {
        params.set(key, String(value));
      }
    });
    return request<PageResult<KnowledgeDirectoryItem>>("/learning/knowledge-points?" + params.toString());
  },
  knowledgeDirectoryFacets: () => request<KnowledgeDirectoryFacets>("/learning/knowledge-points/facets"),
  knowledgeQuestions: (id: string) => request<BrowseQuestion[]>(
    "/learning/knowledge-points/" + encodeURIComponent(id) + "/questions"),
  question: (id: string) => request<BrowseQuestion>("/learning/questions/" + encodeURIComponent(id)),
  knowledgeGuide: (id: string) => request<KnowledgeGuide>(
    "/learning/knowledge-points/" + encodeURIComponent(id) + "/guide"),
  knowledgeNeighbors: (id: string, bookId: string, chapterId: string) => request<KnowledgeNeighbors>(
    `/learning/knowledge-points/${encodeURIComponent(id)}/neighbors?bookId=${encodeURIComponent(bookId)}&chapterId=${encodeURIComponent(chapterId)}`),
  knowledgeState: (id: string) => request<KnowledgeState>(
    "/learner/knowledge-states/" + encodeURIComponent(id)),
  knowledgeStatesForBook: (bookId: string) => request<KnowledgeState[]>(
    "/learner/knowledge-states?bookId=" + encodeURIComponent(bookId)),
  reviewQueue: () => request<ReviewQueue>("/learner/review-queue"),
  progress: () => request<LearnerProgress>("/learner/progress"),
  statistics: (days: 7 | 30 | 90) => request<LearnerStatistics>(`/learner/statistics?days=${days}`),
  wrongQuestions: () => request<WrongQuestion[]>("/learner/wrong-questions"),
  removeWrongQuestion: (questionId: string) => request<void>(
    "/learner/wrong-questions/" + encodeURIComponent(questionId), "DELETE"),
  startKnowledgePractice: (targetKnowledgePointId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "knowledge_drill", targetKnowledgePointId,
    }),
  startWrongPractice: (sourceQuestionId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "wrong_review", sourceQuestionId,
    }),
  /** 快速练习错题：随机连续刷 active 错题，Session 内不重复。 */
  startWrongDrill: () =>
    request<PracticeSession>("/learner/practice-sessions", "POST", { intent: "wrong_drill" }),
  startChapterPractice: (targetBookId: string, targetChapterId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "chapter_drill", targetBookId, targetChapterId,
    }),
  activeChapterPractice: () => request<PracticeSession | undefined>("/learner/practice-sessions/active-chapter"),
  recentChapter: () => request<RecentChapter>("/learner/practice-sessions/recent-chapter"),
  practice: (id: string) => request<PracticeSession>("/learner/practice-sessions/" + encodeURIComponent(id)),
  answerPractice: (session: PracticeSession, answer: unknown) =>
    request<PracticeSession>(`/learner/practice-sessions/${encodeURIComponent(session.id)}/answers`, "POST", {
      attemptId: session.currentAttempt.id, questionId: session.currentAttempt.question.id, answer,
    }),
  revealPractice: (session: PracticeSession) =>
    request<PracticeSession>(`/learner/practice-sessions/${encodeURIComponent(session.id)}/reveal`, "POST", {
      attemptId: session.currentAttempt.id, questionId: session.currentAttempt.question.id,
    }),
  assessPractice: (session: PracticeSession, assessment: string) =>
    request<PracticeSession>(`/learner/practice-sessions/${encodeURIComponent(session.id)}/self-assess`, "POST", {
      attemptId: session.currentAttempt.id, questionId: session.currentAttempt.question.id, assessment,
    }),
  nextPractice: (id: string) =>
    request<PracticeSession>(`/learner/practice-sessions/${encodeURIComponent(id)}/next`, "POST"),
  endPractice: (id: string) =>
    request<PracticeSession>(`/learner/practice-sessions/${encodeURIComponent(id)}/end`, "POST"),
  updateProfile: (profile: StudyProfile, selectedBookIds: string[], focusedKnowledgePointIds: string[]) =>
    request<StudyProfile>("/learner/study-profile", "PUT", {
      pace: profile.pace, difficulty: profile.difficulty, focusMode: profile.focusMode,
      expectedRevision: profile.revision, selectedBookIds, focusedKnowledgePointIds, weights: profile.weights,
    }),
};

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
export interface Chapter { id: string; code: string; name: string; description: string; sortOrder: number; knowledgePoints: KnowledgePoint[] }
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
      knowledgePointId: string; name: string; subject: string; section: string; chapter: string;
      band: MasteryBand; effectiveMastery: number; stabilityDays: number; lastEvidenceAt: string;
    }[];
  };
}
export interface WrongQuestion {
  questionId: string; targetKnowledgePointId: string; knowledgePointName: string;
  contentMarkdown: string; lastGradedAt: string;
}
export interface PracticeAttempt {
  id: string; status: "active" | "revealed" | "graded"; targetKnowledgePointId: string;
  targetKnowledgePointName: string; evidenceMode: "normal" | "training" | "remedial"; diagnosisRole?: string;
  question: {
    id: string; subject: string; chapter: string; presentationType: string; gradingMode: string;
    question: string; options: Record<string, string>; difficulty: number;
  };
  standard?: unknown; explanation?: string; assessment?: "correct" | "partial" | "wrong";
  gradingSource?: string; answerRevealed: boolean;
}
export interface PracticeSession {
  id: string; intent: "knowledge_drill" | "wrong_review" | "chapter_drill"; targetKnowledgePointId?: string;
  targetBookId?: string; targetChapterId?: string; currentKnowledgePointId?: string;
  sourceQuestionId?: string; status: "active" | "ended"; revision: number;
  currentAttempt: PracticeAttempt; flowComplete: boolean; canRepeat: boolean;
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
  startKnowledgePractice: (targetKnowledgePointId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "knowledge_drill", targetKnowledgePointId,
    }),
  startWrongPractice: (sourceQuestionId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "wrong_review", sourceQuestionId,
    }),
  startChapterPractice: (targetBookId: string, targetChapterId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "chapter_drill", targetBookId, targetChapterId,
    }),
  activeChapterPractice: () => request<PracticeSession | undefined>("/learner/practice-sessions/active-chapter"),
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

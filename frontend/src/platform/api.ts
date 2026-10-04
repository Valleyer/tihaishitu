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
export interface BookSummary { id: string; name: string; description: string; revision: number; knowledgePointCount: number; questionCount: number }
export interface Chapter { id: string; parentId?: string; code: string; name: string; description: string; knowledgePoints: KnowledgePoint[]; children: Chapter[] }
export interface BookDetail extends BookSummary { chapters: Chapter[] }
export interface BrowseQuestion {
  id: string; subject: string; sourceType: string; sourceName?: string; questionType: string;
  presentationType: string; gradingMode: string; contentMarkdown: string; analysisMarkdown: string;
  standardAnswer: unknown; difficulty: number; revision: number;
  options?: { key: string; text: string }[]; knowledgePoints?: KnowledgePoint[];
}
export interface HubBootstrap {
  learner: Learner; studyProfile: StudyProfile; worlds: WorldDefinition[];
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
export interface WrongQuestion {
  questionId: string; targetKnowledgePointId: string; knowledgePointName: string;
  subject: string; chapter: string; summary: string; lastGradedAt: string;
}
export interface PracticeAttempt {
  id: string; status: "active" | "revealed" | "graded"; targetKnowledgePointId: string;
  targetKnowledgePointName: string; evidenceMode: "normal" | "training"; diagnosisRole?: string;
  question: {
    id: string; subject: string; chapter: string; presentationType: string; gradingMode: string;
    question: string; options: Record<string, string>; difficulty: number;
  };
  standard?: unknown; explanation?: string; assessment?: "correct" | "partial" | "wrong";
  gradingSource?: string; answerRevealed: boolean;
}
export interface PracticeSession {
  id: string; intent: "knowledge_drill" | "wrong_review"; targetKnowledgePointId: string;
  sourceQuestionId?: string; status: "active" | "ended"; revision: number;
  currentAttempt: PracticeAttempt; flowComplete: boolean; canRepeat: boolean;
}

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
  knowledge: (id: string) => request<KnowledgePoint & { books: { id: string; name: string }[] }>(
    "/learning/knowledge-points/" + encodeURIComponent(id)),
  knowledgeQuestions: (id: string) => request<BrowseQuestion[]>(
    "/learning/knowledge-points/" + encodeURIComponent(id) + "/questions"),
  question: (id: string) => request<BrowseQuestion>("/learning/questions/" + encodeURIComponent(id)),
  knowledgeState: (id: string) => request<KnowledgeState>(
    "/learner/knowledge-states/" + encodeURIComponent(id)),
  knowledgeStatesForBook: (bookId: string) => request<KnowledgeState[]>(
    "/learner/knowledge-states?bookId=" + encodeURIComponent(bookId)),
  reviewQueue: () => request<ReviewQueue>("/learner/review-queue"),
  wrongQuestions: () => request<WrongQuestion[]>("/learner/wrong-questions"),
  startKnowledgePractice: (targetKnowledgePointId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "knowledge_drill", targetKnowledgePointId,
    }),
  startWrongPractice: (sourceQuestionId: string) =>
    request<PracticeSession>("/learner/practice-sessions", "POST", {
      intent: "wrong_review", sourceQuestionId,
    }),
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

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
  updateProfile: (profile: StudyProfile, selectedBookIds: string[], focusedKnowledgePointIds: string[]) =>
    request<StudyProfile>("/learner/study-profile", "PUT", {
      pace: profile.pace, difficulty: profile.difficulty, focusMode: profile.focusMode,
      expectedRevision: profile.revision, selectedBookIds, focusedKnowledgePointIds, weights: profile.weights,
    }),
};

export type ManageUser = {
  id: string;
  username: string;
  displayName: string;
  status: "active" | "disabled";
  roles: string[];
  revision: number;
};

export type PageResult<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type KnowledgeView = {
  id: string;
  code: string;
  name: string;
  subject: string;
  section: string;
  chapter: string;
  defaultRole: "core" | "auxiliary";
  status: "active" | "deprecated";
  description: string;
  explanation: string;
  mergedIntoId?: string;
  aliases: string[];
  questionCount: number;
  revision: number;
};

export type QuestionOption = {
  id?: string;
  key: string;
  text: string;
  correct: boolean;
  sortOrder: number;
};

export type QuestionRelation = {
  id?: string;
  knowledgePointId?: string;
  code?: string;
  name?: string;
  role: "core" | "auxiliary";
  sortOrder: number;
};

export type QuestionView = {
  id: string;
  subject: string;
  sourceType: string;
  sourceName?: string;
  examYear?: number;
  questionNumber?: string;
  questionType: string;
  presentationType: string;
  gradingMode: string;
  content: string;
  standardAnswer: unknown;
  analysis: string;
  difficulty: number;
  status: string;
  createdBy: string;
  creatorName?: string;
  reviewComment?: string;
  revision: number;
  options: QuestionOption[];
  knowledgePoints: QuestionRelation[];
};

let csrf: { headerName: string; token: string } | null = null;

async function ensureCsrf() {
  if (csrf) return csrf;
  const response = await fetch("/api/v1/manage/auth/csrf", {
    credentials: "include",
  });
  if (!response.ok) throw new Error("无法取得管理会话凭证。");
  csrf = await response.json();
  return csrf!;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method || "GET").toUpperCase();
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    const token = await ensureCsrf();
    headers.set(token.headerName, token.token);
  }
  const response = await fetch(`/api/v1/manage${path}`, {
    ...init,
    headers,
    credentials: "include",
  });
  if (response.status === 204) return undefined as T;
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.message || `请求失败（${response.status}）`);
  return data as T;
}

function params(values: Record<string, string | number | undefined>) {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return query.toString();
}

export const manageApi = {
  me: () => request<ManageUser>("/auth/me"),
  async login(username: string, password: string) {
    csrf = null;
    await ensureCsrf();
    return request<ManageUser>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  },
  async logout() {
    await request<void>("/auth/logout", { method: "POST" });
    csrf = null;
  },
  knowledge: (filters: Record<string, string | number | undefined>) =>
    request<PageResult<KnowledgeView>>(`/knowledge-points?${params(filters)}`),
  saveKnowledge: (point: KnowledgeView) =>
    request<KnowledgeView>(`/knowledge-points/${point.id}`, {
      method: "PUT",
      body: JSON.stringify({
        name: point.name,
        defaultRole: point.defaultRole,
        status: point.status,
        description: point.description,
        explanation: point.explanation,
        mergedIntoId: point.mergedIntoId,
        aliases: point.aliases,
        expectedRevision: point.revision,
      }),
    }),
  questions: (filters: Record<string, string | number | undefined>) =>
    request<PageResult<QuestionView>>(`/questions?${params(filters)}`),
  question: (id: string) => request<QuestionView>(`/questions/${id}`),
  createQuestion: (question: Partial<QuestionView>) =>
    request<QuestionView>("/questions", {
      method: "POST",
      body: JSON.stringify(questionPayload(question)),
    }),
  saveQuestion: (question: QuestionView) =>
    request<QuestionView>(`/questions/${question.id}`, {
      method: "PUT",
      body: JSON.stringify({ ...questionPayload(question), expectedRevision: question.revision }),
    }),
  submitQuestion: (question: QuestionView) =>
    request<QuestionView>(`/questions/${question.id}/submit`, {
      method: "POST",
      body: JSON.stringify({ expectedRevision: question.revision }),
    }),
  reviewQuestion: (question: QuestionView, approve: boolean, comment: string) =>
    request<QuestionView>(`/questions/${question.id}/review`, {
      method: "POST",
      body: JSON.stringify({ expectedRevision: question.revision, approve, comment }),
    }),
  users: () => request<ManageUser[]>("/users"),
  createUser: (input: { username: string; displayName: string; password: string; roles: string[] }) =>
    request<ManageUser>("/users", { method: "POST", body: JSON.stringify(input) }),
  saveUser: (user: ManageUser, password = "") =>
    request<ManageUser>(`/users/${user.id}`, {
      method: "PUT",
      body: JSON.stringify({
        displayName: user.displayName,
        status: user.status,
        roles: user.roles,
        password: password || undefined,
        expectedRevision: user.revision,
      }),
    }),
};

function questionPayload(question: Partial<QuestionView>) {
  return {
    subject: question.subject || "数学一",
    sourceType: question.sourceType || "custom",
    sourceName: question.sourceName || "",
    examYear: question.examYear,
    questionNumber: question.questionNumber || "",
    questionType: question.questionType || "single_choice",
    presentationType: question.presentationType || "single_choice",
    gradingMode: question.gradingMode || "auto",
    content: question.content || "",
    standardAnswer: question.standardAnswer ?? "",
    analysis: question.analysis || "",
    difficulty: question.difficulty || 1,
    options: (question.options || []).map((item, index) => ({ ...item, sortOrder: index })),
    knowledgePoints: (question.knowledgePoints || []).map((item, index) => ({
      knowledgePointId: item.knowledgePointId || item.id,
      role: item.role,
      sortOrder: index,
    })),
  };
}

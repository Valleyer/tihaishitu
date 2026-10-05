const maps = {
  questionStatus: {
    draft: "草稿", pending_review: "待审核", published: "已发布",
    rejected: "已退回", archived: "已归档",
  },
  questionType: {
    single_choice: "单选题", multiple_choice: "多选题", true_false: "判断题",
    blank: "历史填空题（需改造）", solution: "综合题",
  },
  presentation: {
    single_choice: "单选作答", multiple_choice: "多选作答", true_false: "判断作答",
    self_assessment: "自评作答",
  },
  grading: { auto: "自动判题", self_assessment: "学习者自评" },
  source: { real_exam: "历年真题", mock: "模拟题", custom: "自建题" },
  knowledgeRole: { core: "核心", auxiliary: "辅助" },
  knowledgeStatus: { active: "有效", deprecated: "已停用或合并" },
  userStatus: { active: "正常", disabled: "已停用" },
  role: { CONTRIBUTOR: "内容贡献者", REVIEWER: "审核员", ADMIN: "管理员" },
  entity: {
    knowledge_point: "知识点", question: "题目", learner_account: "用户",
    app_user: "历史管理用户", question_bank: "文集", question_bank_chapter: "章节",
    question_batch: "题目批次",
  },
} as const;

export type LabelKind = keyof typeof maps;
export function manageLabel(kind: LabelKind, code?: string | null) {
  if (!code) return "—";
  return (maps[kind] as Record<string, string>)[code] || code;
}

export const manageOptions = (kind: LabelKind) =>
  Object.entries(maps[kind])
    .filter(([value]) => kind !== "questionType" || value !== "blank")
    .map(([value, label]) => ({ value, label }));

export function questionTypeContract(questionType: string) {
  if (questionType === "solution") {
    return { presentationType: "self_assessment", gradingMode: "self_assessment" } as const;
  }
  return { presentationType: questionType, gradingMode: "auto" } as const;
}

import type { PracticeAttempt, PracticeIntent, PracticeKnowledgePointTag, RecentChapter } from "./api";

/**
 * 题面来源标题：`2022年考研数学一真题 · 第3题`。
 * examLabel 与 displayQuestionNumber 都由后端按 exam_year + subject_name / 原始题号生成，
 * 前端只负责拼接显示，不通过字符串猜来源，也不自己解析原始题号。
 */
export function examTitle(attempt: PracticeAttempt): string | undefined {
  const label = attempt.examLabel?.trim();
  // UI 只使用 displayQuestionNumber；原始 questionNumber 只作为数据事实。
  const number = attempt.displayQuestionNumber?.trim();
  if (label && number) return `${label} · 第${number}题`;
  if (label) return label;
  if (number) return `第${number}题`;
  return attempt.sourceName?.trim() || undefined;
}

/**
 * 做题页知识点标签：core / auxiliary 全部显示，role 只决定紫色还是青色。
 */
export function attemptKnowledgeTags(attempt: PracticeAttempt): PracticeKnowledgePointTag[] {
  return attempt.knowledgePoints ?? [];
}

export const practiceIntentLabels: Record<PracticeIntent, string> = {
  knowledge_drill: "知识点练习",
  chapter_drill: "章节知识练习",
  wrong_review: "错题重做",
  wrong_drill: "错题快练",
};

export function practiceLabel(intent: PracticeIntent): string {
  return practiceIntentLabels[intent] ?? "专项练习";
}

/**
 * Study 页顶部快捷入口的展示判断。
 * active：恢复同一 Session；last：以相同 Book + Chapter 新建 Session；none：从下方选择文集与章节。
 */
export function recentChapterMode(recent?: RecentChapter | null): "active" | "last" | "none" {
  if (recent?.status === "active" && recent.activeSessionId) return "active";
  if (recent?.status === "last") return "last";
  return "none";
}

/**
 * 章节练习进度显示：`当前进度：2 / 7`，缺少数据时显示占位符。
 *
 * <p>学习页在 PR7 UI 精修中改为「细进度条 + 内嵌百分比」，不再展示 `2 / 7` 这类分式文字，
 * 因此本函数当前无 UI 调用点；保留是因为它是已确认的展示口径，供后续按需复用。</p>
 */
export function chapterProgressText(recent?: RecentChapter | null): string {
  const index = recent?.currentKnowledgePointIndex;
  const total = recent?.knowledgePointCount;
  if (index === null || index === undefined || !total) return "当前进度：—";
  return `当前进度：${index} / ${total}`;
}

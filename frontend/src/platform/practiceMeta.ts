import type { PracticeAttempt, PracticeIntent, PracticeKnowledgePointTag, RecentChapter } from "./api";

/**
 * 题面来源标题：`2022年考研数学一真题 · 第3题`。
 * examLabel 由后端按 exam_year + subject_name 动态生成，前端不通过字符串猜来源。
 * 缺少年份或题号时降级为能确定的部分。
 */
export function examTitle(attempt: PracticeAttempt): string | undefined {
  const label = attempt.examLabel?.trim();
  const number = attempt.questionNumber?.toString().trim();
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

/** 章节练习进度显示：`当前进度：2 / 7`，缺少数据时显示占位符。 */
export function chapterProgressText(recent?: RecentChapter | null): string {
  const index = recent?.currentKnowledgePointIndex;
  const total = recent?.knowledgePointCount;
  if (index === null || index === undefined || !total) return "当前进度：—";
  return `当前进度：${index} / ${total}`;
}

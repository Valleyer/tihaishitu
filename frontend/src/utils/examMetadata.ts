/**
 * 题面 exam metadata 的前端展示规则（Hub 与 World 共用）。
 *
 * 唯一事实来源是后端在发题时冻结进 attempt snapshot 的 examMetadata：
 * - examLabel              真题标签，例如 2022年考研数学一真题
 * - displayQuestionNumber  已剥离同年份前缀的显示题号，例如 "3"
 * - questionNumber         数据库原始题号，只用于 debug / 数据事实
 *
 * 前端不再自己解析原始题号，也不再从来源字符串猜科目。
 */
import type { ExamKnowledgePointTag, ExamMetadata } from "../domain/types";

/** 供做题页顶部使用的稳定视图，字段与后端一致，方便切换数据源。 */
export interface ExamMetadataView {
  examLabel?: string;
  displayQuestionNumber?: string;
  sourceName?: string;
  examYear?: number;
  examTitle?: string;
  knowledgePoints: ExamKnowledgePointTag[];
}

const trimmed = (value?: string | null): string | undefined => {
  const text = value?.trim();
  return text ? text : undefined;
};

/**
 * 题面标题：`2022年考研数学一真题 · 第3题`。
 * 缺少 examLabel 时降级为“第N题”，再缺就退回来源名。
 */
export function examMetadataView(metadata?: ExamMetadata | null): ExamMetadataView | undefined {
  if (!metadata) return undefined;
  const examLabel = trimmed(metadata.examLabel);
  const displayQuestionNumber = trimmed(metadata.displayQuestionNumber);
  const normalizedSourceName = trimmed(metadata.sourceName);
  // presentation 层去重：Attempt snapshot 继续保留完整事实，只有与真题标签
  // trim 后完全相同的来源名不再重复展示。
  const sourceName = normalizedSourceName === examLabel ? undefined : normalizedSourceName;
  const examYear = typeof metadata.examYear === "number" ? metadata.examYear : undefined;
  const title = examLabel && displayQuestionNumber
    ? `${examLabel} · 第${displayQuestionNumber}题`
    : examLabel ?? (displayQuestionNumber ? `第${displayQuestionNumber}题` : sourceName);
  return {
    examLabel, displayQuestionNumber, sourceName, examYear, examTitle: title,
    knowledgePoints: metadata.knowledgePoints ?? [],
  };
}

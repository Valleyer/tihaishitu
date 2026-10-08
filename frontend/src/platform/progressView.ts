import type { MasteryBand, RecentContact } from "./api";

export const progressBandLabels: Record<MasteryBand, string> = {
  unstarted: "尚未稳固",
  unmastered: "尚未稳固",
  learning: "基本掌握",
  ready: "熟练掌握",
  proficient: "彻底掌握",
};

export function progressPercent(value: number, total: number): number {
  return total <= 0 ? 0 : Math.round(value * 100 / total);
}

/**
 * 「最近接触」条目的行为标签。
 *
 * <p>判据是最近一次 Attempt 的真实状态，不是 `evidenceCount`：已有真实评分但暂时没有
 * Mastery Evidence（例如当天首答 wrong 时不新增证据）**不能**被写成「仅查看答案」。</p>
 */
export type RecentContactLabelKind = "reveal_only" | "graded_no_evidence" | "mastery";

export interface RecentContactLabel {
  kind: RecentContactLabelKind;
  /** 简短状态文案；`mastery` 时为空字符串（改由掌握度徽章表达）。 */
  text: string;
}

export function recentContactLabel(contact: RecentContact): RecentContactLabel {
  if (contact.lastOutcomeRevealedOnly) return { kind: "reveal_only", text: "仅查看答案 · 未自评" };
  if (contact.evidenceCount > 0) return { kind: "mastery", text: "" };
  return { kind: "graded_no_evidence", text: contact.lastGraded ? "已作答 · 暂无掌握证据" : "暂无掌握证据" };
}


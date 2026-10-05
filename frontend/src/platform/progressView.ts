import type { MasteryBand } from "./api";

export const progressBandLabels: Record<MasteryBand, string> = {
  unstarted: "未开始",
  unmastered: "尚未稳固",
  learning: "学习中",
  ready: "基本掌握",
  proficient: "熟练掌握",
};

export function progressPercent(value: number, total: number): number {
  return total <= 0 ? 0 : Math.round(value * 100 / total);
}

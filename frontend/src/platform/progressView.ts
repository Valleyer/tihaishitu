import type { MasteryBand } from "./api";

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

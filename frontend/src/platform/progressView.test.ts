import { describe, expect, it } from "vitest";
import { progressBandLabels, progressPercent } from "./progressView";

describe("progress view helpers", () => {
  it("uses the existing mastery bands with positive Chinese labels", () => {
    expect(progressBandLabels).toEqual({
      unstarted: "尚未稳固", unmastered: "尚未稳固", learning: "基本掌握",
      ready: "熟练掌握", proficient: "彻底掌握",
    });
  });

  it("renders an empty learning scope without a fake percentage", () => {
    expect(progressPercent(0, 0)).toBe(0);
    expect(progressPercent(3, 10)).toBe(30);
  });
});

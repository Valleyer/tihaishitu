import { describe, expect, it } from "vitest";
import { activities } from "./index";

describe("activity reward normalization", () => {
  it("keeps one positive reward tier for every repeatable activity", () => {
    for (const activity of activities.filter(item => item.activityMode === "repeatable")) {
      expect(activity.tiers.filter(tier => tier.minScore > 0)).toHaveLength(1);
      expect(activity.tiers.filter(tier => tier.minScore > 0)[0].minScore).toBe(activity.passScore);
    }
  });

  it("keeps task completion rewards", () => {
    for (const activity of activities.filter(item => item.activityMode === "task"))
      expect(activity.completionReward).toBeDefined();
  });
});

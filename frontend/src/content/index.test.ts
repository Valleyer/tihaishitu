import { describe, expect, it } from "vitest";
import { activities, exams, items, mapDesign } from "./index";

describe("activity reward normalization", () => {
  it("keeps one positive reward tier for every repeatable activity", () => {
    for (const activity of activities.filter(item => item.activityMode === "repeatable")) {
      expect(activity.tiers.filter(tier => tier.minScore > 0)).toHaveLength(1);
      expect(activity.tiers.filter(tier => tier.minScore > 0)[0].minScore).toBe(activity.passScore);
    }
  });

  it("keeps task completion rewards", () => {
    for (const activity of activities.filter(item => item.activityMode === "task")) {
      expect(activity.completionReward).toBeDefined();
      expect(activity.completionReward?.reputation).toBeGreaterThan(0);
      expect(activity.completionReward).not.toHaveProperty("knowledge");
      expect(activity.completionReward).not.toHaveProperty("coins");
      expect(activity.completionReward).not.toHaveProperty("attributes");
    }
  });

  it("enforces the four-growth canonical content contract", () => {
    const read = activities.find((activity) => activity.id === "read")!;
    const copyWork = activities.find((activity) => activity.id === "copy-work")!;
    expect(read.tiers.at(-1)?.rewards).toEqual({ knowledge: 5 });
    expect(copyWork.tiers.at(-1)?.rewards).toEqual({ coins: 4 });
    expect(activities.filter((activity) => ["read", "copy-work"].includes(activity.id))).toHaveLength(2);
    expect(activities.some((activity) => ["compose", "calculate", "review", "study-night-watch"].includes(activity.id))).toBe(false);

    for (const activity of activities) {
      expect(activity.requirements.attributes).toBeUndefined();
      for (const tier of activity.tiers) {
        expect(tier.rewards.attributes).toBeUndefined();
        expect(tier.firstRewards?.attributes).toBeUndefined();
      }
      if (activity.kind === "companion" && activity.activityMode === "repeatable")
        expect(Object.keys(activity.tiers.at(-1)?.rewards || {})).toEqual(["favorability"]);
    }
    for (const location of mapDesign.locations)
      expect(location.requirements.attributes).toBeUndefined();
    for (const exam of exams) {
      expect(exam.requirements.attributes).toBeUndefined();
      expect(exam.meritTitle).toBeTruthy();
    }
    for (const item of items) {
      expect(item.bonuses).toBeUndefined();
      expect(item.use?.attributes).toBeUndefined();
    }
  });
});

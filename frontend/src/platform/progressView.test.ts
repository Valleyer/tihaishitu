import { describe, expect, it } from "vitest";
import type { RecentContact } from "./api";
import { progressBandLabels, progressPercent, recentContactLabel } from "./progressView";

const contact = (overrides: Partial<RecentContact> = {}): RecentContact => ({
  knowledgePointId: "point", name: "知识点", bookName: "文集", chapterName: "章节",
  band: "unstarted", effectiveMastery: 0, stabilityDays: 0, evidenceCount: 0,
  lastEvidenceAt: null, lastEffectiveContactAt: "2026-10-05T01:00:00Z",
  lastOutcomeRevealedOnly: false, lastGraded: false, assessment: null,
  ...overrides,
});

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

describe("recent contact labels", () => {
  it("describes a reveal-only contact without inventing mastery", () => {
    expect(recentContactLabel(contact({ lastOutcomeRevealedOnly: true })))
      .toEqual({ kind: "reveal_only", text: "仅查看答案 · 未自评" });
  });

  it("does not call a graded contact reveal-only just because it has no evidence", () => {
    // 这是复核必修 2 的核心回归：evidenceCount=0 但最近一次是真实评分。
    expect(recentContactLabel(contact({ lastGraded: true, assessment: "wrong" })))
      .toEqual({ kind: "graded_no_evidence", text: "已作答 · 暂无掌握证据" });
  });

  it("falls back to a neutral label when the contact was neither graded nor revealed", () => {
    expect(recentContactLabel(contact())).toEqual({ kind: "graded_no_evidence", text: "暂无掌握证据" });
  });

  it("prefers real mastery whenever evidence exists", () => {
    expect(recentContactLabel(contact({ evidenceCount: 3, band: "ready", effectiveMastery: 80 })))
      .toEqual({ kind: "mastery", text: "" });
  });
});


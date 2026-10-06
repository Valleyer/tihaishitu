import { describe, expect, it } from "vitest";
import { attemptKnowledgeTags, chapterProgressText, examTitle, practiceLabel, recentChapterMode } from "./practiceMeta";
import type { PracticeAttempt, RecentChapter } from "./api";

const attempt = (overrides: Partial<PracticeAttempt> = {}): PracticeAttempt => ({
  id: "attempt", status: "active", targetKnowledgePointId: "point", targetKnowledgePointName: "函数",
  evidenceMode: "normal",
  question: { id: "q", subject: "数学一", chapter: "函数", presentationType: "single_choice",
    gradingMode: "auto", question: "题干", options: {}, difficulty: 2 },
  answerRevealed: false, ...overrides,
});

describe("practice metadata", () => {
  it("composes the exam title from the backend label and question number", () => {
    expect(examTitle(attempt({ examLabel: "2022年考研数学一真题", questionNumber: "3" })))
      .toBe("2022年考研数学一真题 · 第3题");
    expect(examTitle(attempt({ examLabel: "2024年408考研真题", questionNumber: "16" })))
      .toBe("2024年408考研真题 · 第16题");
  });

  it("degrades to the parts that are actually known", () => {
    expect(examTitle(attempt({ examLabel: "2022年考研数学一真题" }))).toBe("2022年考研数学一真题");
    expect(examTitle(attempt({ questionNumber: "3" }))).toBe("第3题");
    expect(examTitle(attempt({ sourceName: "全服题库" }))).toBe("全服题库");
    expect(examTitle(attempt())).toBeUndefined();
  });

  it("keeps core and auxiliary knowledge point tags together", () => {
    expect(attemptKnowledgeTags(attempt())).toEqual([]);
    expect(attemptKnowledgeTags(attempt({ knowledgePoints: [
      { id: "k1", name: "数列极限计算", role: "core" },
      { id: "k2", name: "函数奇偶性", role: "auxiliary" },
    ] }))).toHaveLength(2);
  });

  it("labels every practice intent", () => {
    expect(practiceLabel("knowledge_drill")).toBe("知识点练习");
    expect(practiceLabel("chapter_drill")).toBe("章节知识练习");
    expect(practiceLabel("wrong_review")).toBe("错题重做");
    expect(practiceLabel("wrong_drill")).toBe("错题快练");
  });

  it("maps the recent chapter status to the Study entry mode", () => {
    const active: RecentChapter = { status: "active", activeSessionId: "s1", bookName: "数学一" };
    const last: RecentChapter = { status: "last", lastSessionId: "s0", bookId: "math" };
    expect(recentChapterMode(active)).toBe("active");
    expect(recentChapterMode(last)).toBe("last");
    expect(recentChapterMode({ status: "none" })).toBe("none");
    expect(recentChapterMode(undefined)).toBe("none");
    // 没有 activeSessionId 时不能误判成可继续。
    expect(recentChapterMode({ status: "active" })).toBe("none");
  });

  it("renders chapter progress without inventing numbers", () => {
    expect(chapterProgressText({ status: "active", currentKnowledgePointIndex: 2, knowledgePointCount: 7 }))
      .toBe("当前进度：2 / 7");
    expect(chapterProgressText({ status: "last" })).toBe("当前进度：—");
  });
});

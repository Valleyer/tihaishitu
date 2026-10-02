import { describe, expect, it } from "vitest";
import type { Bank, Game, Learning } from "../domain/types";
import { eligibleQuestions } from "./QuestionEngine";
import {
  isLearningMastered,
  learningErrorRate,
} from "./SpacedRepetitionEngine";

const learning = (attempts: number, wrong: number): Learning => ({
  attempts,
  correct: attempts - wrong,
  wrong,
  errorRate: learningErrorRate({ attempts, wrong }),
  streak: 0,
  lastIndex: 0,
  dueAt: 0,
  lastAt: "2026-01-01T00:00:00.000Z",
  wrongAnswers: [],
  reviewCount: 0,
});

describe("题目掌握规则", () => {
  it("按配置的 3/0、5/1、10/3 档位判断", () => {
    expect(isLearningMastered(learning(3, 0))).toBe(true);
    expect(isLearningMastered(learning(3, 1))).toBe(false);
    expect(isLearningMastered(learning(5, 1))).toBe(true);
    expect(isLearningMastered(learning(5, 2))).toBe(false);
    expect(isLearningMastered(learning(10, 3))).toBe(true);
    expect(isLearningMastered(learning(10, 4))).toBe(false);
    expect(learningErrorRate(learning(5, 1))).toBe(20);
  });

  it("达到掌握标准的题目不再进入候选池", () => {
    const bank = {
      id: "demo",
      enabled: true,
      weight: 1,
      questions: [
        {
          id: "q1",
          subject: "经义",
          type: "true_false",
          enabled: true,
        },
      ],
    } as Bank;
    const game = {
      config: { bankIds: ["demo"], weights: { 经义: 1 } },
      learning: { "demo::q1": learning(3, 0) },
    } as unknown as Game;

    expect(eligibleQuestions(game, [bank])).toHaveLength(0);
    expect(eligibleQuestions(game, [bank], true)).toHaveLength(1);
  });
});

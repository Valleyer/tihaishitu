import { describe, expect, it } from "vitest";
import { createLocalApi, STORAGE_KEY } from "./store";

function setup() {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
  };
  return { storage, api: createLocalApi(storage) };
}
describe("local answer lifecycle", () => {
  it("persists results and prevents duplicate rewards on retry or reload", async () => {
    const { api, storage } = setup();
    const session = await api.getSession();
    const input = {
      attemptId: session.attemptId,
      questionId: session.question.id,
      answer: "B",
    };
    const first = await api.submitAnswer(input);
    await api.submitAnswer(input);
    const restored = await createLocalApi(storage).getSession();
    expect(restored).toEqual(first);
    expect(restored.player.knowledge).toBe(3);
    expect(await api.getStatistics()).toMatchObject({
      total: 1,
      correct: 1,
      streak: 1,
    });
    expect(restored.question).not.toHaveProperty("answer");
  });
  it("records mistakes without blocking progress and makes next retries idempotent", async () => {
    const { api } = setup();
    const session = await api.getSession();
    await api.submitAnswer({
      attemptId: session.attemptId,
      questionId: session.question.id,
      answer: "A",
    });
    expect(await api.getMistakes()).toMatchObject([
      { wrongCount: 1, correctAnswer: "B" },
    ]);
    const next = await api.nextQuestion(session.attemptId);
    expect(next.question.id).not.toBe(session.question.id);
    expect(await api.nextQuestion(session.attemptId)).toEqual(next);
    expect(await api.getStatistics()).toMatchObject({
      total: 1,
      correct: 0,
      streak: 0,
    });
  });
  it("rejects unanswered next, invalid options and stale submissions", async () => {
    const { api } = setup();
    const session = await api.getSession();
    await expect(api.nextQuestion(session.attemptId)).rejects.toThrow();
    await expect(
      api.submitAnswer({
        attemptId: session.attemptId,
        questionId: session.question.id,
        answer: "Z",
      }),
    ).rejects.toThrow();
    await expect(
      api.submitAnswer({
        attemptId: "stale",
        questionId: session.question.id,
        answer: "B",
      }),
    ).rejects.toThrow();
    expect((await api.getStatistics()).total).toBe(0);
  });
  it("does not overwrite corrupt saves", async () => {
    const { api, storage } = setup();
    storage.setItem(STORAGE_KEY, "broken");
    await expect(api.getSession()).rejects.toThrow("本地存档无法读取");
    expect(storage.getItem(STORAGE_KEY)).toBe("broken");
  });
});

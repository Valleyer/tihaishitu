/**
 * 最小回归集：只覆盖发卷判题、随机选项、存档快照和导入边界，不做长时间刷题模拟。
 */
import { describe, expect, it } from "vitest";
import { createLocalApi, STORAGE_KEY } from "./store";
import { validateAnswer } from "../../engine/AnswerValidator";
import {
  normalizeQuestion,
  parseBank,
  exportCSV,
} from "../../engine/QuestionBankManager";
import { shuffleQuestion, displayAnswer } from "../../engine/OptionShuffler";
import type { NewGame } from "../../domain/types";
const config: NewGame = {
  name: "测试书生",
  gender: "女",
  origin: "寒门读书人",
  bankIds: ["math", "cs", "politics", "english"],
  weights: { 数学一: 7, "408": 7, 政治: 3, 英语一: 3 },
  pace: "normal",
  difficulty: "gentle",
};
function setup() {
  const values = new Map<string, string>();
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
  };
  return { api: createLocalApi(storage), storage };
}
describe("核心答题与存档", () => {
  it("判题只结算一次，刷新保持结果；修订题库不改变已发出的卷", async () => {
    const { api, storage } = setup();
    const game = await api.createGame(config),
      attempt = game.attempt!;
    const [bankId, id] = attempt.question.id.split("::");
    const bank = (await api.bootstrap()).banks.find((b) => b.id === bankId)!;
    const answer = bank.questions.find((q) => q.id === id)!.answer;
    expect(attempt.question).not.toHaveProperty("answer");
    await api.putBank({
      ...bank,
      questions: bank.questions.filter((q) => q.id !== id),
    });
    const result = await api.answer(game.id, { attemptId: attempt.id, answer });
    const duplicate = await api.answer(game.id, {
      attemptId: attempt.id,
      answer,
    });
    expect(result.attempt!.result!.correct).toBe(true);
    expect(duplicate.records).toHaveLength(1);
    expect(duplicate.player.knowledge).toBe(result.player.knowledge);
    expect(
      (await createLocalApi(storage).getGame(game.id)).attempt?.result,
    ).toEqual(result.attempt?.result);
    const next = await api.next(game.id, attempt.id);
    expect((await api.next(game.id, attempt.id)).attempt!.id).toBe(
      next.attempt!.id,
    );
    const restored = await api.importSave(await api.exportSave(game.id));
    expect(restored.id).not.toBe(game.id);
    expect(restored.config.bankIds[0]).not.toBe(game.config.bankIds[0]);
    expect(restored.records).toHaveLength(1);
  });
  it("判断题 false 可以提交并判为正确", async () => {
    const { api, storage } = setup();
    const bank = parseBank(
      JSON.stringify([
        {
          id: "false",
          type: "true_false",
          question: "1 等于 2。",
          answer: false,
        },
      ]),
      "json",
      "判断卷",
    );
    await api.putBank(bank);
    const game = await api.createGame({ ...config, bankIds: [bank.id] });
    // 模拟旧版本未保存判断选项的存档，确认继续游戏时补齐按钮。
    const old = JSON.parse(storage.getItem(STORAGE_KEY)!);
    old.saves[game.id].attempt.question.options = {};
    old.snapshots[game.id].options = {};
    storage.setItem(STORAGE_KEY, JSON.stringify(old));
    expect(Object.keys((await api.getGame(game.id)).attempt!.question.options)).toHaveLength(2);
    const result = await api.answer(game.id, {
      attemptId: game.attempt!.id,
      answer: false,
    });
    expect(result.attempt!.result!.correct).toBe(true);
  });
  it("洗牌改变顺序但不改变正确键；多选按集合判分", () => {
    const question = normalizeQuestion(
      {
        type: "multiple_choice",
        question: "选择",
        options: { A: "甲", B: "乙", C: "丙" },
        answer: ["A", "C"],
      },
      0,
    );
    const shuffled = shuffleQuestion(
      question,
      Object.keys(question.options),
      () => 0.99,
    );
    expect(Object.keys(shuffled.options)).not.toEqual(
      Object.keys(question.options),
    );
    expect(shuffled.answer).toEqual(["A", "C"]);
    expect(validateAnswer(shuffled, ["C", "A"])).toBe(true);
    expect(validateAnswer(shuffled, ["A"])).toBe(false);
    expect(displayAnswer("A", shuffled.options)).toBe("C · 甲");
  });
});
describe("题库入口", () => {
  it("CSV 的引号、换行和判断答案能够往返导入", () => {
    const bank = parseBank(
      JSON.stringify([
        {
          id: "q1",
          type: "true_false",
          question: "第一行,有逗号\n第二行",
          answer: false,
          explanation: '解析"引号"',
        },
        {
          id: "q2",
          type: "single_choice",
          question: "名词",
          options: { A: "Cache", B: "CPU" },
          answer: "A",
        },
      ]),
      "json",
      "测试",
    );
    expect(parseBank(exportCSV(bank), "csv", "还原").questions).toEqual(
      bank.questions,
    );
  });
  it("拒绝不支持的题型和无效答案，不导入半份题库", () => {
    expect(() =>
      parseBank(
        '[{"type":"short_answer","question":"名词","answer":"Cache"}]',
        "json",
        "无效",
      ),
    ).toThrow("仅支持");
    expect(() =>
      parseBank(
        '[{"type":"single_choice","question":"选择","options":{"A":"甲","B":"乙"},"answer":"C"}]',
        "json",
        "无效",
      ),
    ).toThrow("已有选项");
    expect(() =>
      parseBank('question,answer\n"unclosed,a', "csv", "无效"),
    ).toThrow("闭合");
  });
});

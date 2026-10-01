/** 核心回归：仅覆盖新循环的结算、门槛和迁移，不做长时间刷题模拟。 */
import { expect, it } from "vitest";
import { createLocalApi, STORAGE_KEY } from "./store";
import type { Game, GameApi, NewGame } from "../../domain/types";
import { effectiveAttribute } from "../../engine/AdventureEngine";
const config: NewGame = {
  name: "游历测试",
  gender: "女",
  origin: "寒门读书人",
  bankIds: ["math"],
  weights: { 数学一: 1 },
  pace: "normal",
  difficulty: "gentle",
};
function setup() {
  const values = new Map<string, string>(),
    storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => {
        values.set(key, value);
      },
    };
  return { api: createLocalApi(storage), storage };
}
async function play(api: GameApi, game: Game, correctCount: number) {
  const run = game.adventure!.run!,
    bankList = (await api.bootstrap()).banks;
  for (let index = 0; index < run.definition.rounds; index++) {
    const question = game.attempt!.question,
      [bankId, id] = question.id.split("::");
    const original = bankList
      .find((b) => b.id === bankId)!
      .questions.find((q) => q.id === id)!;
    const wrong =
      original.type === "true_false"
        ? !original.answer
        : original.type === "multiple_choice"
          ? Array.isArray(original.answer) && original.answer.length === 1
            ? Object.keys(original.options)
            : [String((original.answer as string[])[0])]
          : Object.keys(original.options).find((k) => k !== original.answer)!;
    game = await api.answer(game.id, {
      attemptId: game.attempt!.id,
      answer: index < correctCount ? original.answer : wrong,
    });
    if (index < run.definition.rounds - 1)
      game = await api.next(game.id, game.attempt!.id);
  }
  return game;
}
it("默认探索，读书完成后结算属性银两；结束重试不重复领取", async () => {
  const { api } = setup();
  let game = await api.createGame(config);
  expect(game.attempt).toBeNull();
  expect(game.adventure!.run).toBeNull();
  game = await api.beginActivity(game.id, "read");
  await expect(api.travel(game.id, "east-hall")).rejects.toThrow("行程");
  game = await play(api, game, 3);
  expect(game.adventure!.run!.score).toBe(100);
  expect(game.adventure!.attributes.insight).toBe(4);
  const coins = game.player.coins,
    runId = game.adventure!.run!.id;
  const duplicate = await api.answer(game.id, {
    attemptId: game.attempt!.id,
    answer: game.attempt!.result!.answer,
  });
  expect(duplicate.player.coins).toBe(coins);
  await api.finishActivity(game.id, runId);
  game = await api.finishActivity(game.id, runId);
  expect(game.player.coins).toBe(coins);
  expect(game.attempt).toBeNull();
});
it("共读按整轮评分加好感；副本专属奖励只发一次，装备能解锁地点", async () => {
  const { api } = setup();
  let game = await api.createGame(config);
  game = await api.beginActivity(game.id, "read-lu");
  game = await play(api, game, 2);
  expect(game.npcs.find((n) => n.id === "lu")!.affinity).toBe(0);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  game = await api.beginActivity(game.id, "read-lu");
  game = await play(api, game, 4);
  expect(game.npcs.find((n) => n.id === "lu")!.affinity).toBe(4);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  await expect(api.travel(game.id, "library")).rejects.toThrow("悟性");
  for (let repeat = 0; repeat < 2; repeat++) {
    game = await api.beginActivity(game.id, "trial-ink");
    game = await play(api, game, 4);
    game = await api.finishActivity(game.id, game.adventure!.run!.id);
  }
  expect(game.adventure!.inventory.inkstone).toBe(1);
  game = await api.useItem(game.id, "inkstone");
  expect(effectiveAttribute(game, "insight")).toBe(9);
  expect((await api.travel(game.id, "library")).adventure!.locationId).toBe(
    "library",
  );
});
it("旧人生保留历史与关系并回到世界；探索进度随备份保留", async () => {
  const { api, storage } = setup();
  const game = await api.createGame(config);
  const old = JSON.parse(storage.getItem(STORAGE_KEY)!);
  delete old.saves[game.id].adventure;
  old.saves[game.id].player.knowledge = 42;
  old.saves[game.id].npcs = old.saves[game.id].npcs.filter(
    (n: { id: string }) => n.id !== "lin",
  );
  storage.setItem(STORAGE_KEY, JSON.stringify(old));
  const loaded = await api.getGame(game.id);
  expect(loaded.player.knowledge).toBe(42);
  expect(loaded.attempt).toBeNull();
  expect(loaded.npcs.some((n) => n.id === "lin")).toBe(true);
  const moved = await api.travel(game.id, "east-hall");
  const restored = await api.importSave(await api.exportSave(moved.id));
  expect(restored.adventure!.locationId).toBe("east-hall");
  expect(restored.adventure!.visited).toContain("east-hall");
});
it("县试报名、落榜温卷、重考取中形成完整闭环", async () => {
  const { api, storage } = setup();
  let game = await api.createGame(config);
  // 只准备考试门槛，避免测试重复模拟日常刷题；正式应试仍走真实发题与判分。
  const db = JSON.parse(storage.getItem(STORAGE_KEY)!);
  db.saves[game.id].player.knowledge = 35;
  db.saves[game.id].player.reputation = 3;
  db.saves[game.id].adventure.attributes = {
    insight: 6,
    eloquence: 4,
    craft: 4,
  };
  storage.setItem(STORAGE_KEY, JSON.stringify(db));
  game = await api.getGame(game.id);
  game = await api.travel(game.id, "exam-street");
  const coins = game.player.coins;
  game = await api.registerExam(game.id, "county-exam");
  expect(game.player.coins).toBe(coins - 12);
  expect(game.adventure!.exams["county-exam"].status).toBe("registered");

  game = await api.beginActivity(game.id, "county-exam-paper");
  game = await play(api, game, 6);
  expect(game.adventure!.exams["county-exam"].status).toBe("preparing");
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  await expect(
    api.beginActivity(game.id, "county-exam-paper"),
  ).rejects.toThrow("应试资格");

  game = await api.beginActivity(game.id, "county-exam-prep");
  game = await play(api, game, 2);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  expect(game.adventure!.exams["county-exam"].status).toBe("registered");

  game = await api.beginActivity(game.id, "county-exam-paper");
  game = await play(api, game, 7);
  expect(game.adventure!.exams["county-exam"].status).toBe("passed");
  expect(game.player.title).toBe("县试取中");
  expect(game.flags).toContain("county-exam-passed");
  expect(game.adventure!.inventory["county-pass-note"]).toBe(1);
  expect(game.adventure!.exams["county-exam"].attempts).toBe(2);
});

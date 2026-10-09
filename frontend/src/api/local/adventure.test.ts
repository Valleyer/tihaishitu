/** 核心回归：仅覆盖新循环的结算、门槛和迁移，不做长时间刷题模拟。 */
import { expect, it } from "vitest";
import { createLocalApi, STORAGE_KEY } from "./store";
import type { Game, GameApi, NewGame } from "../../domain/types";
import { flagRequirementName } from "../../engine/AdventureEngine";
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
it("所有剧情前置 flag 都通过统一展示映射转为中文任务名", () => {
  expect(flagRequirementName("letter-read")).toBe("门内的一封信");
  expect(flagRequirementName("county-exam-passed")).toBe("青溪县试正卷");
  expect(flagRequirementName("medicine-entrusted")).toBe("廊下的药香");
  expect(flagRequirementName("book-box-found")).toBe("桥下的书箱");
  expect(flagRequirementName("warehouse-clue")).toBe("县仓熄灯之后");
  expect(flagRequirementName("lantern-clue")).toBe("灯谜摊前的一场争执");
  expect(flagRequirementName("unknown-internal-id")).toBe("一段前置剧情");
});
async function play(api: GameApi, game: Game, correctCount: number) {
  const bankList = (await api.bootstrap()).banks;
  let safety = 0;
  while (game.adventure!.run!.status === "active") {
    if (++safety > 100) throw new Error("知识点训练没有在预期次数内结束");
    const run = game.adventure!.run!;
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
      // 首题按目标成绩作答；诊断训练题答对后才能进入下一知识点。
      answer:
        run.training || run.knowledgePointIndex < correctCount
          ? original.answer
          : wrong,
    });
    if (game.adventure!.run!.status === "active")
      game = await api.next(game.id, game.attempt!.id);
  }
  return game;
}
/** 长流程测试模拟玩家回应自动弹出的际遇，再继续下一段行程。 */
async function answerEvent(api: GameApi, game: Game) {
  return game.event
    ? api.choose(game.id, game.event.id, game.event.options[0].id)
    : game;
}
it("潜心读书整轮只结算学识，单题不再改变核心资源", async () => {
  const { api } = setup();
  let game = await api.createGame(config);
  expect(game.attempt).toBeNull();
  expect(game.adventure!.run).toBeNull();
  game = await api.beginActivity(game.id, "read");
  await expect(api.travel(game.id, "east-hall")).rejects.toThrow("行程");
  game = await play(api, game, 5);
  expect(game.adventure!.run!.score).toBe(100);
  // repeatable 满分仍只发原最低通过档，不再叠加第二档奖励。
  expect(game.player.knowledge).toBe(5);
  expect(game.player.reputation).toBe(0);
  expect(game.adventure!.attributes).toEqual({});
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
it("抄书谋生整轮只结算银两", async () => {
  const { api } = setup();
  let game = await api.createGame(config);
  const before = { ...game.player };
  game = await api.beginActivity(game.id, "copy-work");
  game = await play(api, game, 5);
  expect(game.player.coins).toBe(before.coins + 4);
  expect(game.player.knowledge).toBe(before.knowledge);
  expect(game.player.reputation).toBe(before.reputation);
  expect(game.adventure!.attributes).toEqual({});
});
it("活动结束后触发城中际遇，选择会开启对应人物支线", async () => {
  const { api } = setup();
  let game = await api.createGame(config);
  game = await api.beginActivity(game.id, "read");
  game = await play(api, game, 3);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  expect(game.event?.id).toBe("medicine-errand");
  game = await api.choose(game.id, game.event!.id, "deliver");
  expect(game.event).toBeNull();
  expect(game.flags).toContain("medicine-entrusted");
});
it("共读按整轮评分加好感；副本完成后奖励一次并永久结案", async () => {
  const { api } = setup();
  let game = await api.createGame(config);
  game = await api.beginActivity(game.id, "read-lu");
  game = await play(api, game, 2);
  expect(game.npcs.find((n) => n.id === "lu")!.favorability).toBe(0);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  game = await answerEvent(api, game);
  game = await api.beginActivity(game.id, "read-lu");
  game = await play(api, game, 4);
  // 送药际遇 +2 与本轮共读 +2 现在统一累计到同一个好感度字段。
  expect(game.npcs.find((n) => n.id === "lu")!.favorability).toBe(4);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  game = await answerEvent(api, game);
  await expect(api.travel(game.id, "library")).rejects.toThrow("学识");
  const beforeTask = { ...game.player };
  game = await api.beginActivity(game.id, "trial-ink");
  game = await play(api, game, 5);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  game = await answerEvent(api, game);
  expect(game.adventure!.inventory.inkstone).toBe(1);
  expect(game.adventure!.clears["trial-ink"]).toBe(1);
  expect(game.player.knowledge).toBe(beforeTask.knowledge);
  expect(game.player.coins).toBe(beforeTask.coins);
  expect(game.player.reputation).toBe(beforeTask.reputation + 2);
  await expect(api.beginActivity(game.id, "trial-ink")).rejects.toThrow(
    "这项任务已经完成",
  );
  await expect(api.useItem(game.id, "inkstone")).rejects.toThrow("纪念信物");
});
it("旧 attributes/equipped 存档可读，非装备引用被清理且不绕过学识门槛", async () => {
  const { api, storage } = setup();
  let game = await api.createGame(config);
  const db = JSON.parse(storage.getItem(STORAGE_KEY)!);
  db.saves[game.id].adventure.attributes = { insight: 99, eloquence: 99, craft: 99 };
  db.saves[game.id].adventure.inventory.inkstone = 1;
  db.saves[game.id].adventure.equipped = { desk: "inkstone" };
  storage.setItem(STORAGE_KEY, JSON.stringify(db));
  game = await api.getGame(game.id);
  expect(game.adventure!.attributes.insight).toBe(99);
  expect(game.adventure!.equipped).toEqual({});
  await expect(api.travel(game.id, "library")).rejects.toThrow("学识");
  const updated = JSON.parse(storage.getItem(STORAGE_KEY)!);
  updated.saves[game.id].player.knowledge = 15;
  storage.setItem(STORAGE_KEY, JSON.stringify(updated));
  expect((await api.travel(game.id, "library")).adventure!.locationId).toBe(
    "library",
  );
});
it("旧存档中的 active run 保留开始时冻结的奖励承诺", async () => {
  const { api, storage } = setup();
  let game = await api.createGame(config);
  game = await api.beginActivity(game.id, "trial-ink");
  const db = JSON.parse(storage.getItem(STORAGE_KEY)!);
  db.saves[game.id].adventure.run.definition.completionReward = {
    reputation: 7,
    attributes: { insight: 2 },
  };
  db.saves[game.id].adventure.run.definition.successDialogue = "旧旅程的承诺";
  storage.setItem(STORAGE_KEY, JSON.stringify(db));
  game = await api.getGame(game.id);
  expect(game.adventure!.run!.definition.completionReward).toEqual({
    reputation: 7,
    attributes: { insight: 2 },
  });
  expect(game.adventure!.run!.definition.successDialogue).toBe("旧旅程的承诺");
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
it("县试未完成可重试，取中后任务永久结案", async () => {
  const { api, storage } = setup();
  let game = await api.createGame(config);
  // 只准备考试门槛，避免测试重复模拟日常刷题；正式应试仍走真实发题与判分。
  const db = JSON.parse(storage.getItem(STORAGE_KEY)!);
  db.saves[game.id].player.knowledge = 35;
  db.saves[game.id].player.reputation = 3;
  storage.setItem(STORAGE_KEY, JSON.stringify(db));
  game = await api.getGame(game.id);
  game = await api.travel(game.id, "exam-street");
  const coins = game.player.coins;
  game = await api.registerExam(game.id, "county-exam");
  expect(game.player.coins).toBe(coins);
  expect(game.adventure!.exams["county-exam"].status).toBe("registered");

  game = await api.beginActivity(game.id, "county-exam-paper");
  game = await play(api, game, 9);
  expect(game.adventure!.exams["county-exam"].status).toBe("registered");
  expect(game.player.coins).toBe(coins);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  game = await answerEvent(api, game);

  game = await api.beginActivity(game.id, "county-exam-paper");
  game = await play(api, game, 10);
  expect(game.adventure!.exams["county-exam"].status).toBe("passed");
  expect(game.player.title).toBe("青溪县试案首");
  expect(game.flags).toContain("county-exam-passed");
  expect(game.adventure!.inventory["county-pass-note"]).toBe(1);
  expect(game.adventure!.exams["county-exam"].attempts).toBe(0);
  game = await api.finishActivity(game.id, game.adventure!.run!.id);
  await expect(api.beginActivity(game.id, "county-exam-paper")).rejects.toThrow("已经完成");
});

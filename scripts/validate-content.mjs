// 内容编辑后的轻量检查：检查引用、题型与素材路径；不运行游戏模拟。
import fs from "node:fs";
const root = new URL("../frontend/src/content/", import.meta.url);
const read = (name) =>
  JSON.parse(
    fs
      .readFileSync(new URL(name + ".json", root), "utf8")
      .replace(/^\uFEFF/, ""),
  );
const errors = [];
const check = (condition, message) => {
  if (!condition) errors.push(message);
};
const chapters = read("chapters"),
  characters = read("characters"),
  scenes = read("scenes"),
  events = read("events"),
  maps = read("maps"),
  portraits = read("portraits"),
  banks = read("question-banks"),
  game = read("game");
const unique = (items, name) =>
  check(
    new Set(items.map((i) => i.id)).size === items.length,
    name + " 的 id 不可重复",
  );
for (const [items, name] of [
  [chapters, "chapters"],
  [characters, "characters"],
  [events, "events"],
  [maps.locations, "maps"],
  [banks, "question-banks"],
])
  unique(items, name);
check(chapters.length > 0, "至少需要一章");
chapters.forEach((c, i) => {
  check(
    maps.locations.some((l) => l.id === c.locationId),
    c.id + " 引用了不存在的地点",
  );
  check(
    scenes.groups.some((g) => g.id === c.sceneGroup),
    c.id + " 引用了不存在的剧情组",
  );
  check(c.intro?.lines?.length > 0, c.id + " 缺少入章对话");
  check(
    i === 0 || c.threshold > chapters[i - 1].threshold,
    "章节题数门槛必须递增",
  );
  check(c.knowledge >= 0 && c.trust >= 0, c.id + " 的门槛不能为负");
});
characters.forEach((n) =>
  check(n.portrait in portraits.portraits, n.id + " 的立绘未配置"),
);
scenes.groups.forEach((g) => {
  check(g.scenes.length > 0, "剧情组 " + g.id + " 为空");
  g.scenes.forEach((s) => {
    check(
      characters.some((n) => n.id === s.npcId),
      s.id + " 的人物不存在",
    );
    for (const k of ["dialogue", "context", "success", "failure"])
      check(typeof s[k] === "string" && s[k].length > 0, s.id + " 缺少 " + k);
  });
});
events.forEach((e) => {
  check(e.options.length >= 2, e.id + " 至少需要两个回应");
  e.options.forEach((o) => {
    for (const k of ["trust", "affinity"])
      for (const id of Object.keys(o.effects[k] || {}))
        check(
          characters.some((n) => n.id === id),
          e.id + " 的奖励引用了不存在的人物 " + id,
        );
  });
});
for (const bank of banks) {
  unique(bank.questions, bank.id);
  for (const q of bank.questions) {
    check(
      ["single_choice", "multiple_choice", "true_false"].includes(q.type),
      q.id + " 仅支持判断、单选、多选",
    );
    check(
      typeof q.question === "string" && q.question.length > 0,
      q.id + " 题干为空",
    );
    if (q.type === "true_false")
      check(typeof q.answer === "boolean", q.id + " 判断题答案必须是布尔值");
    else {
      const keys = Object.keys(q.options);
      check(keys.length >= 2 && keys.length <= 6, q.id + " 需要 2–6 个选项");
      check(
        keys.every((k) => /^[A-F]$/.test(k)),
        q.id + " 选项标识只能是 A–F",
      );
      const answers = Array.isArray(q.answer) ? q.answer : [q.answer];
      check(
        answers.length > 0 && answers.every((a) => keys.includes(a)),
        q.id + " 答案与选项不匹配",
      );
    }
    check(
      q.frequency >= 1 &&
        q.frequency <= 5 &&
        q.difficulty >= 1 &&
        q.difficulty <= 5,
      q.id + " 难度和频率应为 1–5",
    );
  }
}
check(
  game.calendar.normal > 0 && game.calendar.slow > 0,
  "每一天的题数必须大于 0",
);
check(
  game.growth.difficultyDivisor > 0 && game.growth.reputationEvery > 0,
  "成长计算的除数必须大于 0",
);
for (const path of [
  game.titleBackground,
  portraits.sheet,
  ...maps.locations.map((l) => l.background),
]) {
  check(path.startsWith("/art/"), "素材请放在 public/art 内：" + path);
  check(
    fs.existsSync(new URL("../frontend/public" + path, import.meta.url)),
    "素材不存在：" + path,
  );
}
if (errors.length) {
  console.error("配置检查失败：\n" + errors.map((e) => " - " + e).join("\n"));
  process.exitCode = 1;
} else
  console.log(
    "配置有效：" +
      chapters.length +
      " 章 / " +
      characters.length +
      " 人物 / " +
      events.length +
      " 际遇 / " +
      banks.reduce((sum, b) => sum + b.questions.length, 0) +
      " 道题",
  );

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
  const points = bank.knowledgePoints || [];
  if (points.length) unique(points, bank.id + " knowledge-points");
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
    if (points.length) {
      check(
        Array.isArray(q.knowledgePointIds) &&
          q.knowledgePointIds.length >= 1 &&
          q.knowledgePointIds.length <= 3,
        q.id + " 应关联 1–3 个知识点",
      );
      check(
        (q.knowledgePointIds || []).every((id) =>
          points.some((point) => point.id === id),
        ),
        q.id + " 引用了不存在的知识点",
      );
    }
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
  // 新版头像是独立方图，不再依赖旧版横向图集。
  ...Object.values(portraits.portraits).map((portrait) => portrait.src),
  ...maps.locations.map((l) => l.background),
]) {
  check(path.startsWith("/art/"), "素材请放在 public/art 内：" + path);
  check(
    fs.existsSync(new URL("../frontend/public" + path, import.meta.url)),
    "素材不存在：" + path,
  );
}

// V2—V6：配置引用在构建时一次检查，避免手改后进入副本才发现漏写奖励或人物。
const activities = [...read("activities"), ...read("activities-v7")],
  items = [...read("items"), ...read("items-v7")],
  companions = read("companions"),
  exams = read("exams"),
  adventure = read("adventure");
unique(activities, "activities");
unique(items, "items");
unique(exams, "exams");
for (const location of maps.locations) {
  const nameParts = location.name.split(" · ");
  check(
    nameParts.length === 2 && nameParts.every(Boolean),
    `地点名称必须使用“大地图名 · 小地点名”格式：${location.id}`,
  );
}
const attrIds = adventure.attributes.map((a) => a.id),
  npcIds = characters.map((n) => n.id),
  itemIds = items.map((i) => i.id),
  locIds = maps.locations.map((l) => l.id);
const checkReward = (reward, source) => {
  if (reward.title !== undefined)
    check(
      typeof reward.title === "string" && reward.title.length > 0,
      source + " 身份称号不能为空",
    );
  for (const key of ["knowledge", "coins", "reputation"])
    if (reward[key] !== undefined)
      check(
        Number.isFinite(reward[key]) && reward[key] >= 0,
        source + " 奖励 " + key + " 必须非负",
      );
  for (const [id, value] of Object.entries(reward.attributes || {}))
    check(
      attrIds.includes(id) && Number.isFinite(value) && value >= 0,
      source + " 属性奖励无效：" + id,
    );
  for (const [id, value] of Object.entries(reward.items || {}))
    check(
      itemIds.includes(id) && Number.isInteger(value) && value > 0,
      source + " 道具奖励无效：" + id,
    );
  for (const key of ["affinity", "trust"])
    for (const [id, value] of Object.entries(reward[key] || {}))
      check(
        npcIds.includes(id) && Number.isFinite(value) && value >= 0,
        source + " 关系奖励无效：" + id,
      );
};
const checkGate = (gate, source) => {
  for (const id of gate.items || [])
    check(itemIds.includes(id), source + " 门槛道具不存在：" + id);
  for (const [id, min] of Object.entries(gate.attributes || {}))
    check(
      attrIds.includes(id) && Number.isFinite(min) && min >= 0,
      source + " 属性门槛无效：" + id,
    );
  for (const [id, min] of Object.entries(gate.affinity || {}))
    check(
      npcIds.includes(id) && Number.isFinite(min) && min >= 0,
      source + " 好感门槛无效：" + id,
    );
};
check(locIds.includes(adventure.startLocation), "初始地图不存在");
for (const a of activities) {
  check(
    ["study", "companion", "dungeon", "story", "exam"].includes(a.kind),
    a.id + " 活动类型无效",
  );
  check(
    Number.isInteger(a.rounds) && a.rounds > 0 && a.rounds <= 50,
    a.id + " 轮数须为 1–50",
  );
  check(a.passScore >= 0 && a.passScore <= 100, a.id + " 通关分数无效");
  check(!a.locationId || locIds.includes(a.locationId), a.id + " 地点不存在");
  check(!a.npcId || npcIds.includes(a.npcId), a.id + " 人物不存在");
  checkGate(a.requirements, a.id);
  check(
    a.tiers.length > 0 && a.tiers.some((t) => t.minScore === 0),
    a.id + " 需要 0 分兜底档",
  );
  check(
    new Set(a.tiers.map((t) => t.minScore)).size === a.tiers.length,
    a.id + " 评分档不能重复",
  );
  for (const tier of a.tiers) {
    check(tier.minScore >= 0 && tier.minScore <= 100, a.id + " 评分档无效");
    checkReward(tier.rewards, a.id);
    if (tier.firstRewards) checkReward(tier.firstRewards, a.id);
  }
}
for (const exam of exams) {
  check(locIds.includes(exam.locationId), exam.id + " 报名地点不存在");
  check(
    activities.some((a) => a.id === exam.activityId && a.kind === "exam"),
    exam.id + " 正试活动无效",
  );
  check(
    activities.some((a) => a.id === exam.preparationActivityId),
    exam.id + " 落榜备考活动无效",
  );
  check(Number.isFinite(exam.fee) && exam.fee >= 0, exam.id + " 报名费无效");
  checkGate(exam.requirements, exam.id);
  for (const status of ["unregistered", "registered", "preparing", "passed"])
    check(
      typeof exam.dialogues?.[status] === "string" && exam.dialogues[status].length > 0,
      exam.id + " 缺少状态对白 " + status,
    );
}
for (const c of companions) {
  check(
    npcIds.includes(c.npcId) && locIds.includes(c.locationId),
    "人物互动引用不存在",
  );
  for (const id of c.activities)
    check(
      activities.some((a) => a.id === id && a.npcId === c.npcId),
      "共读活动引用无效：" + id,
    );
  for (const t of c.topics) check(t.lines.length > 0, "人物话题不能为空");
  for (const m of c.milestones) checkReward(m.reward, c.npcId);
}
for (const item of items) {
  if (item.kind === "equipment") {
    check(!!item.slot, item.id + " 装备缺少部位");
    for (const [id, n] of Object.entries(item.bonuses || {}))
      check(attrIds.includes(id) && n >= 0, item.id + " 装备属性无效");
  }
  if (item.use) checkReward(item.use, item.id);
}
for (const l of maps.locations) {
  checkGate(l.requirements, l.id);
  for (const id of l.npcs) check(npcIds.includes(id), l.id + " 人物不存在");
}
console.log(
  "探索内容：" +
    activities.length +
    " 项活动 / " + exams.length + " 场科举 / " +
    items.length +
    " 件物品 / " +
    companions.length +
    " 位可交互人物",
);
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

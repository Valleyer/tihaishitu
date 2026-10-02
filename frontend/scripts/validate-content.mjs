import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const content = join(root, "src", "content");
const errors = [];
const read = (name) => {
  try {
    return JSON.parse(readFileSync(join(content, name), "utf8"));
  } catch (error) {
    errors.push(`${name}: JSON 无法读取（${error.message}）`);
    return [];
  }
};
const many = (prefix) =>
  readdirSync(content)
    .filter((name) => name === `${prefix}.json` || name.startsWith(`${prefix}-v`))
    .sort()
    .flatMap(read);
const unique = (values, label) => {
  const seen = new Set();
  for (const value of values) {
    if (!value?.id) errors.push(`${label}: 存在缺少 id 的配置`);
    else if (seen.has(value.id)) errors.push(`${label}: id 重复 ${value.id}`);
    seen.add(value?.id);
  }
  return seen;
};
const has = (set, id, at) => {
  if (id && !set.has(id)) errors.push(`${at}: 引用了不存在的 id ${id}`);
};

const maps = read("maps.json");
const regions = unique(maps.regions || [], "大地图");
const locations = unique(maps.locations || [], "地点");
const characters = read("characters.json");
const npcIds = unique(characters, "人物");
const portraits = read("portraits.json").portraits || {};
const activities = many("activities");
const activityIds = unique(activities, "活动");
const items = many("items");
const itemIds = unique(items, "物品");
const companions = many("companions");
const exams = read("exams.json");
unique(exams, "科举");

for (const location of maps.locations || []) {
  has(regions, location.regionId, `地点 ${location.id}`);
  if (!location.name?.includes(" · "))
    errors.push(`地点 ${location.id}: name 必须使用“大地图名 · 小地点名”`);
  for (const npc of location.npcs || []) has(npcIds, npc, `地点 ${location.id}`);
  const file = join(root, "public", location.background?.replace(/^\//, "") || "");
  if (!existsSync(file)) errors.push(`地点 ${location.id}: 背景文件不存在 ${location.background}`);
}
for (const region of maps.regions || []) {
  const file = join(root, "public", region.background?.replace(/^\//, "") || "");
  if (!existsSync(file)) errors.push(`大地图 ${region.id}: 底图文件不存在 ${region.background}`);
}
for (const npc of characters) {
  if (!portraits[npc.portrait]) errors.push(`人物 ${npc.id}: 头像配置不存在 ${npc.portrait}`);
}
for (const [id, portrait] of Object.entries(portraits)) {
  const file = join(root, "public", portrait.src?.replace(/^\//, "") || "");
  if (!existsSync(file)) errors.push(`头像 ${id}: 文件不存在 ${portrait.src}`);
}
for (const activity of activities) {
  has(locations, activity.locationId, `活动 ${activity.id}`);
  has(npcIds, activity.npcId, `活动 ${activity.id}`);
  if (!activity.tiers?.some((tier) => tier.minScore === 0))
    errors.push(`活动 ${activity.id}: 缺少 0 分结算档`);
  for (const tier of activity.tiers || [])
    for (const item of Object.keys(tier.firstRewards?.items || {}))
      has(itemIds, item, `活动 ${activity.id}`);
}
for (const companion of companions) {
  has(npcIds, companion.npcId, `故人配置 ${companion.npcId}`);
  has(locations, companion.locationId, `故人配置 ${companion.npcId}`);
  for (const activity of companion.activities || [])
    has(activityIds, activity, `故人配置 ${companion.npcId}`);
  for (const milestone of companion.milestones || [])
    for (const item of Object.keys(milestone.reward?.items || {}))
      has(itemIds, item, `故人里程碑 ${companion.npcId}`);
}
for (const exam of exams) {
  has(locations, exam.locationId, `科举 ${exam.id}`);
  has(activityIds, exam.activityId, `科举 ${exam.id}`);
  has(activityIds, exam.preparationActivityId, `科举 ${exam.id}`);
}
for (const bank of read("question-banks.json")) {
  const points = unique(bank.knowledgePoints || [], `文集 ${bank.id} 知识点`);
  for (const question of bank.questions || []) {
    if (question.knowledgePointIds?.length < 1 || question.knowledgePointIds?.length > 3)
      errors.push(`题目 ${question.id}: knowledgePointIds 必须为 1–3 个`);
    for (const point of question.knowledgePointIds || [])
      has(points, point, `题目 ${question.id}`);
  }
}

if (errors.length) {
  console.error("内容配置校验失败：\n- " + errors.join("\n- "));
  process.exit(1);
}
console.log(`内容配置校验通过：${regions.size} 张大地图、${locations.size} 个地点、${activityIds.size} 项活动、${npcIds.size} 位人物、${exams.length} 场科举。`);

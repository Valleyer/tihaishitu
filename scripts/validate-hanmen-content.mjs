// PR9 寒门仕途长期规则。root / frontend validator 共同调用，避免 canonical schema 漂移。
export const HANMEN_ACTIVITY_KINDS = new Set([
  "study",
  "work",
  "exam-prep",
  "companion",
  "dungeon",
  "story",
  "exam",
]);

const LEGACY_ATTRIBUTES = new Set(["insight", "eloquence", "craft"]);
const TASK_REWARD_KEYS = new Set([
  "reputation",
  "favorability",
  "items",
  "flags",
  "title",
]);

const presentKeys = (value = {}) =>
  Object.keys(value).filter((key) => {
    const entry = value[key];
    if (entry === undefined || entry === null) return false;
    if (Array.isArray(entry)) return entry.length > 0;
    if (typeof entry === "object") return Object.keys(entry).length > 0;
    return true;
  });

const hasLegacyAttributes = (value) =>
  Object.keys(value?.attributes || {}).some((key) => LEGACY_ATTRIBUTES.has(key));

const hasAnyAttributes = (value) =>
  Object.keys(value?.attributes || {}).length > 0;

const positiveTiers = (activity) =>
  (activity?.tiers || []).filter((tier) => tier.minScore > 0);

const rewardBlocks = (activity) =>
  (activity?.tiers || []).flatMap((tier) =>
    tier.firstRewards ? [tier.rewards || {}, tier.firstRewards] : [tier.rewards || {}],
  );

const isExactReward = (reward, expected) => {
  const keys = presentKeys(reward).sort();
  const expectedKeys = Object.keys(expected).sort();
  return (
    JSON.stringify(keys) === JSON.stringify(expectedKeys) &&
    expectedKeys.every((key) => reward?.[key] === expected[key])
  );
};

export function validateHanmenContent({
  activities = [],
  adventure = {},
  companions = [],
  events = [],
  exams = [],
  items = [],
  maps = {},
}) {
  const errors = [];
  const fail = (message) => errors.push(message);

  if ((adventure.attributes || []).length > 0)
    fail("canonical adventure.attributes 必须为空；旧字段仅允许在存档兼容层读取");

  for (const location of maps.locations || [])
    if (hasAnyAttributes(location.requirements))
      fail(`地点 ${location.id}: canonical 门槛不得使用旧三本领`);

  for (const exam of exams) {
    if (hasAnyAttributes(exam.requirements))
      fail(`科举 ${exam.id}: 报名资格不得使用旧三本领`);
    if (!exam.meritTitle) fail(`科举 ${exam.id}: 缺少 meritTitle`);
  }

  const activityById = new Map(activities.map((activity) => [activity.id, activity]));
  for (const removed of ["compose", "calculate", "review", "study-night-watch"])
    if (activityById.has(removed))
      fail(`旧普通读书 ${removed}: 不得留在 canonical activities`);

  for (const activity of activities) {
    if (!HANMEN_ACTIVITY_KINDS.has(activity.kind))
      fail(`${activity.id} 活动类型无效`);
    if (hasAnyAttributes(activity.requirements))
      fail(`活动 ${activity.id}: canonical 门槛不得使用旧三本领`);

    for (const reward of rewardBlocks(activity)) {
      if (hasAnyAttributes(reward))
        fail(`活动 ${activity.id}: canonical reward 不得使用旧三本领`);
      if (activity.activityMode !== "task" && presentKeys(reward).includes("reputation"))
        fail(`活动 ${activity.id}: 非 task reward 不得奖励声望`);
      if (
        activity.activityMode === "repeatable" &&
        presentKeys(reward).includes("knowledge") &&
        activity.id !== "read"
      )
        fail(`活动 ${activity.id}: repeatable 不得奖励学识，唯一来源是潜心读书`);
      if (
        activity.activityMode === "repeatable" &&
        presentKeys(reward).includes("coins") &&
        activity.id !== "copy-work"
      )
        fail(`活动 ${activity.id}: repeatable 不得奖励银两，唯一来源是抄书谋生`);
    }

    if (activity.activityMode === "task") {
      const success = positiveTiers(activity);
      if (success.length !== 1 || success[0].minScore !== activity.passScore)
        fail(`任务 ${activity.id}: canonical 只能有一个完成档`);
      for (const reward of rewardBlocks(activity)) {
        const invalid = presentKeys(reward).filter((key) => !TASK_REWARD_KEYS.has(key));
        if (invalid.length > 0)
          fail(`任务 ${activity.id}: 完成奖励类型无效 ${invalid.join(", ")}`);
      }
      for (const tier of (activity.tiers || []).filter((entry) => entry.minScore === 0))
        for (const reward of tier.firstRewards
          ? [tier.rewards || {}, tier.firstRewards]
          : [tier.rewards || {}])
          if (presentKeys(reward).length > 0)
            fail(`任务 ${activity.id}: 失败档不得发放奖励`);
      if ((success[0]?.rewards?.reputation || 0) < 1)
        fail(`任务 ${activity.id}: 完成奖励至少需要声望 +1`);
    }

    if (activity.kind === "companion" && activity.activityMode === "repeatable")
      for (const reward of rewardBlocks(activity)) {
        const invalid = presentKeys(reward).filter((key) => key !== "favorability");
        if (invalid.length > 0)
          fail(`故人活动 ${activity.id}: repeatable reward 只能有好感度`);
      }
  }

  const assertCoreActivity = (id, kind, expectedReward, label) => {
    const activity = activityById.get(id);
    if (!activity) {
      fail(`${label}: canonical 活动缺失`);
      return;
    }
    const success = positiveTiers(activity);
    const failureRewards = (activity.tiers || [])
      .filter((tier) => tier.minScore !== 60)
      .flatMap((tier) =>
        tier.firstRewards
          ? [tier.rewards || {}, tier.firstRewards]
          : [tier.rewards || {}],
      );
    if (
      activity.kind !== kind ||
      activity.activityMode !== "repeatable" ||
      activity.rounds !== 5 ||
      activity.passScore !== 60 ||
      success.length !== 1 ||
      success[0].minScore !== 60 ||
      !isExactReward(success[0].rewards || {}, expectedReward) ||
      presentKeys(success[0].firstRewards || {}).length > 0 ||
      failureRewards.some((reward) => presentKeys(reward).length > 0)
    )
      fail(`${label}: 必须为 repeatable、5 道、60 分通关且只奖励 ${id === "read" ? "学识 +5" : "银两 +4"}`);
  };
  assertCoreActivity("read", "study", { knowledge: 5 }, "潜心读书");
  assertCoreActivity("copy-work", "work", { coins: 4 }, "抄书谋生");

  for (const item of items) {
    if (Object.keys(item.bonuses || {}).some((key) => LEGACY_ATTRIBUTES.has(key)))
      fail(`物品 ${item.id}: canonical bonuses 不得使用旧三本领`);
    const invalidUse = presentKeys(item.use || {}).filter((key) =>
      ["knowledge", "coins", "reputation", "attributes"].includes(key),
    );
    if (invalidUse.length > 0 || hasLegacyAttributes(item.use))
      fail(`物品 ${item.id}: canonical use 不得奖励核心资源或旧三本领`);
  }

  for (const companion of companions)
    for (const milestone of companion.milestones || []) {
      const invalid = presentKeys(milestone.reward || {}).filter((key) =>
        ["knowledge", "coins", "reputation", "attributes"].includes(key),
      );
      if (invalid.length > 0 || hasLegacyAttributes(milestone.reward))
        fail(`故人里程碑 ${companion.npcId}: 不得奖励核心资源或旧三本领`);
    }

  for (const event of events)
    for (const option of event.options || []) {
      const invalid = presentKeys(option.effects || {}).filter((key) =>
        ["knowledge", "coins", "reputation", "attributes"].includes(key),
      );
      if (invalid.length > 0 || hasLegacyAttributes(option.effects))
        fail(`际遇 ${event.id}/${option.id}: 不得奖励核心资源或旧三本领`);
    }

  return errors;
}

/**
 * 探索循环：看见目标 → 自主进入活动 → 完成一轮答题 → 按实际正确率结算 → 回到世界。
 * 奖励只在最后一题判完时发一次；返回世界、读档、重复点击不会再次领取。
 * 装备加成只参与属性门槛，不替玩家提高答题成绩。
 */
import type { Game } from "../domain/types";
import type { Activity, Requirements, Rewards } from "../domain/adventure";
import {
  activities,
  adventureDesign,
  characterDesign,
  companions,
  items,
  mapDesign,
  gameDesign,
  exams,
} from "../content";

const blankExamRecord = () => ({
  status: "unregistered" as const,
  attempts: 0,
  best: 0,
  lastScore: 0,
});

export function hydrateAdventure(game: Game) {
  if (!game.adventure) {
    // 老版的未交课卷退出；历史答题、关系、钱和章节全部保留。默认回到世界，不再自动发卷。
    game.adventure = {
      version: 6,
      locationId: adventureDesign.startLocation,
      visited: [adventureDesign.startLocation],
      attributes: {},
      inventory: {},
      equipped: {},
      best: {},
      clears: {},
      rewardClaims: [],
      conversations: {},
      seenEncounters: [],
      encounter: null,
      run: null,
      exams: {},
    };
    game.attempt = null;
  }
  // V5 存档原地补齐县试字段；玩家已有的关系、物品、成绩和行程全部保留。
  game.adventure.version = 6;
  game.adventure.exams ??= {};
  for (const exam of exams)
    game.adventure.exams[exam.id] ??= blankExamRecord();
  // 旧版落榜要求先温卷；新版主线考试不惩罚，名帖保留并可直接重试。
  for (const record of Object.values(game.adventure.exams))
    if (record.status === "preparing") record.status = "registered";
  for (const npc of game.npcs) {
    const legacy = npc as typeof npc & { affinity?: number; trust?: number };
    npc.favorability = Math.max(
      0,
      Math.min(
        100,
        Number.isFinite(npc.favorability)
          ? npc.favorability
          : Math.max(legacy.affinity || 0, legacy.trust || 0),
      ),
    );
    delete legacy.affinity;
    delete legacy.trust;
  }
  for (const character of characterDesign)
    if (!game.npcs.some((n) => n.id === character.id))
      game.npcs.push(structuredClone(character));
  // 旧存档可保留 equipped 引用，但 canonical 已转为非装备的物品必须安全清理。
  for (const [slot, itemId] of Object.entries(game.adventure.equipped || {})) {
    const item = items.find((candidate) => candidate.id === itemId);
    if (!item || item.kind !== "equipment" || item.slot !== slot)
      delete game.adventure.equipped[slot];
  }
  for (const [id, count] of Object.entries(game.adventure.clears))
    if (count > 0 && activities.find((activity) => activity.id === id)?.activityMode === "task")
      game.adventure.clears[id] = 1;
  if (game.adventure.run) {
    const run = game.adventure.run;
    const canonical = activities.find((activity) => activity.id === run.definition.id);
    run.entryCost ??= 0;
    run.costCommitted ??= false;
    run.costRefunded ??= false;
    if (canonical) {
      // An active run is a frozen promise. Only backfill fields absent from an
      // older snapshot; never replace its requirements, rewards or dialogue.
      for (const key of [
        "activityMode",
        "completionReward",
        "successDialogue",
        "failureDialogue",
      ] as const)
        if (!(key in run.definition) && canonical[key] !== undefined)
          Object.assign(run.definition, { [key]: structuredClone(canonical[key]) });
    }
  }
}
const legacyAttributeNames: Record<string, string> = {
  insight: "悟性",
  eloquence: "辞采",
  craft: "筹算",
};
export const attributeName = (id: string) => legacyAttributeNames[id] || id;
export const itemName = (id: string) =>
  items.find((item) => item.id === id)?.name || id;
export function effectiveAttribute(game: Game, id: string) {
  return (
    (game.adventure?.attributes[id] || 0) +
    Object.values(game.adventure?.equipped || {}).reduce(
      (sum, itemId) =>
        sum + (items.find((i) => i.id === itemId)?.bonuses?.[id] || 0),
      0,
    )
  );
}
export function requirementIssues(
  game: Game,
  requirements: Requirements,
): string[] {
  const issues: string[] = [];
  if (game.player.knowledge < (requirements.knowledge || 0))
    issues.push("学识 " + game.player.knowledge + "/" + requirements.knowledge);
  if (game.player.reputation < (requirements.reputation || 0))
    issues.push(
      "声望 " + game.player.reputation + "/" + requirements.reputation,
    );
  for (const [id, min] of Object.entries(requirements.attributes || {}))
    if (effectiveAttribute(game, id) < min)
      issues.push(
        attributeName(id) + " " + effectiveAttribute(game, id) + "/" + min,
      );
  for (const [id, min] of Object.entries(requirements.favorability || {})) {
    const npc = game.npcs.find((n) => n.id === id);
    if ((npc?.favorability || 0) < min)
      issues.push(
        (npc?.name || id) + "好感度 " + (npc?.favorability || 0) + "/" + min,
      );
  }
  for (const id of requirements.items || [])
    if (!((game.adventure?.inventory[id] || 0) > 0))
      issues.push("需要 " + itemName(id));
  for (const flag of requirements.flags || [])
    if (!game.flags.includes(flag)) {
      const prior = activities.find((a) =>
        a.tiers.some((t) => t.rewards.flags?.includes(flag)),
      );
      issues.push("先完成「" + (prior?.name || flag) + "」");
    }
  return issues;
}
export function activityIssues(game: Game, activity: Activity) {
  const issues = requirementIssues(game, activity.requirements);
  const location = mapDesign.locations.find(
    (l) => l.id === activity.locationId,
  );
  if (location) issues.push(...requirementIssues(game, location.requirements));
  if (activity.activityMode === "task" && (game.adventure?.clears[activity.id] || 0) > 0)
    issues.push("这项任务已完成");
  return [...new Set(issues)];
}
/** 奖励预览和实际结算共用同一套字段，不根据文案猜奖励。 */
export function rewardLines(reward: Rewards): string[] {
  const lines: string[] = [];
  if (reward.title) lines.push("身份 · " + reward.title);
  for (const [key, label] of [
    ["knowledge", "学识"],
    ["coins", adventureDesign.currency.name],
    ["reputation", "声望"],
  ] as const)
    if (reward[key]) lines.push(label + " +" + reward[key]);
  for (const [id, value] of Object.entries(reward.attributes || {}))
    lines.push(attributeName(id) + " +" + value);
  for (const [id, value] of Object.entries(reward.favorability || {}))
    lines.push((characterDesign.find((n) => n.id === id)?.name || id) + "好感度 +" + value);
  for (const [id, value] of Object.entries(reward.items || {}))
    lines.push(itemName(id) + " ×" + value);
  return lines;
}
export function grantRewards(game: Game, reward: Rewards): string[] {
  const state = game.adventure!;
  for (const key of ["knowledge", "coins", "reputation"] as const)
    game.player[key] = Math.max(0, game.player[key] + (reward[key] || 0));
  for (const [id, value] of Object.entries(reward.attributes || {}))
    state.attributes[id] = (state.attributes[id] || 0) + value;
  for (const [id, value] of Object.entries(reward.favorability || {})) {
    const npc = game.npcs.find((n) => n.id === id);
    if (npc) {
      npc.met = true;
      npc.favorability = Math.max(
        0,
        Math.min(gameDesign.growth.relationshipMax, npc.favorability + value),
      );
    }
  }
  for (const [id, value] of Object.entries(reward.items || {}))
    state.inventory[id] = (state.inventory[id] || 0) + value;
  game.flags = [...new Set([...game.flags, ...(reward.flags || [])])];
  if (reward.title) game.player.title = reward.title;
  return rewardLines(reward);
}
/** 报名只确认资格；报名银在开考时进入本轮托管。 */
export function registerExam(game: Game, id: string) {
  const exam = exams.find((e) => e.id === id);
  if (!exam) throw new Error("这场考试尚未开放。");
  const state = game.adventure!, record = state.exams[id];
  if (state.run) throw new Error("请先结束眼前的行程。");
  if (state.locationId !== exam.locationId) throw new Error("请先到试院前巷报名。");
  if (record.status !== "unregistered")
    throw new Error(record.status === "passed" ? "你已经取中。" : "名帖已经递入试院。");
  const issues = requirementIssues(game, exam.requirements);
  if (issues.length) throw new Error(issues.join("；"));
  record.status = "registered";
  game.journal.push({
    id: crypto.randomUUID(),
    day: game.records.length,
    kind: "milestone",
    title: exam.name + " · 投递名帖",
    text: "名帖已经验明。开考时才暂收报名银，未完成或中途退出会原数退回。",
  });
}
export function beginRun(game: Game, id: string) {
  const activity = activities.find((a) => a.id === id);
  if (!activity) throw new Error("这项活动暂不可用。");
  if (game.adventure!.run)
    throw new Error("还有一段未结束的行程，请先继续或收起。");
  if (activity.activityMode === "task" && (game.adventure!.clears[id] || 0) > 0)
    throw new Error("这项任务已经完成。");
  const exam = exams.find((e) => e.activityId === id);
  if (exam && game.adventure!.exams[exam.id].status !== "registered")
    throw new Error("须先取得本场应试资格。落榜后需完成温卷再来。");
  const preparation = exams.find((e) => e.preparationActivityId === id);
  if (preparation && game.adventure!.exams[preparation.id].status !== "preparing")
    throw new Error("眼下无需闭门温卷，先去试院看看吧。");
  const issues = activityIssues(game, activity);
  if (issues.length) throw new Error(issues.join("；"));
  if (activity.locationId && game.adventure!.locationId !== activity.locationId)
    throw new Error("请先前往活动所在地点。");
  const entryCost = activity.activityMode === "task" ? exam?.fee || activity.entryCost || 0 : 0;
  if (game.player.coins < entryCost) throw new Error("入场银两不足。");
  game.player.coins -= entryCost;
  game.adventure!.run = {
    id: crypto.randomUUID(),
    definition: structuredClone(activity),
    answered: 0,
    correct: 0,
    knowledgePointIds: [],
    knowledgePointIndex: 0,
    training: false,
    trainingAnswered: 0,
    diagnosticAnswered: 0,
    diagnosisSessionId: null,
    seenQuestionIds: [],
    status: "active",
    score: 0,
    grade: "",
    rewards: [],
    response: "",
    entryCost,
    costCommitted: false,
    costRefunded: false,
  };
  game.adventure!.encounter = null;
  // 兼容旧际遇：先在世界中回应，不让它中途截断一轮挑战。
  if (game.event) throw new Error("请先在世界中回应尚未结束的际遇。");
}
export function settleRunAnswer(
  game: Game,
  correct: boolean,
  questionId: string,
) {
  const state = game.adventure!,
    run = state.run;
  if (!run || run.status !== "active")
    throw new Error("当前没有正在进行的活动。");
  run.answered++;
  run.seenQuestionIds.push(questionId);
  if (run.training) {
    run.trainingAnswered++;
    if (correct) {
      run.training = false;
      run.knowledgePointIndex++;
    }
  } else if (correct) {
    run.correct++;
    run.knowledgePointIndex++;
  } else {
    // 首题失分后不跳过：进入同知识点训练，直到真正答对再继续。
    run.training = true;
    run.trainingAnswered++;
  }
  if (run.knowledgePointIndex < run.knowledgePointIds.length) return;
  run.score = Math.round((run.correct / run.knowledgePointIds.length) * 100);
  const tiers = [...run.definition.tiers].sort(
    (a, b) => a.minScore - b.minScore,
  );
  const tier = tiers.filter((t) => run.score >= t.minScore).at(-1)!;
  run.grade = tier.label;
  const task = run.definition.activityMode === "task";
  const completed = run.score >= run.definition.passScore;
  run.response = task
    ? completed
      ? run.definition.successDialogue || tier.dialogue
      : run.definition.failureDialogue || tier.dialogue
    : tier.dialogue;
  run.rewards = [];
  if (task) {
    if (completed) {
      if (!(state.clears[run.definition.id] > 0))
        run.rewards = grantRewards(game, run.definition.completionReward || {});
      state.clears[run.definition.id] = 1;
      run.costCommitted = true;
    } else refundRunCost(game, run);
  } else {
    run.rewards = grantRewards(game, tier.rewards);
    for (const reached of tiers.filter((t) => run.score >= t.minScore)) {
      const key = run.definition.id + ":" + reached.minScore;
      if (reached.firstRewards && !state.rewardClaims.includes(key)) {
        run.rewards.push(...grantRewards(game, reached.firstRewards));
        state.rewardClaims.push(key);
      }
    }
  }
  state.best[run.definition.id] = Math.max(
    state.best[run.definition.id] || 0,
    run.score,
  );
  if (!task && completed)
    state.clears[run.definition.id] =
      (state.clears[run.definition.id] || 0) + 1;
  // 考试结算在普通活动奖励之后落档，确保揭榜、身份和奖励是一次事务。
  const exam = exams.find((e) => e.activityId === run.definition.id);
  if (exam) {
    const record = state.exams[exam.id];
    record.best = Math.max(record.best, run.score);
    if (task) {
      if (completed) record.status = "passed";
      else if (record.status !== "passed") record.status = "registered";
    } else {
      record.attempts++;
      record.lastScore = run.score;
      if (completed) record.status = "passed";
    }
  }
  const preparation = exams.find(
    (e) => e.preparationActivityId === run.definition.id,
  );
  if (
    preparation &&
    run.score >= run.definition.passScore &&
    state.exams[preparation.id].status === "preparing"
  ) {
    state.exams[preparation.id].status = "registered";
    run.response += " 名帖仍在册中，明日便可再入试院。";
  }
  run.status = "settled";
  if (!task || completed)
    game.journal.push({
      id: crypto.randomUUID(),
      day: game.records.length,
      kind: "milestone",
      title: run.definition.name + " · " + run.grade,
      text:
        run.score +
        " 分。" +
        run.response +
        " " +
        (run.rewards.join("，") || "今日又有所得。"),
    });
}

export function refundRunCost(game: Game, run = game.adventure?.run) {
  if (!run || run.entryCost <= 0 || run.costCommitted || run.costRefunded) return;
  game.player.coins += run.entryCost;
  run.costRefunded = true;
}
export function travelTo(game: Game, id: string) {
  const state = game.adventure!;
  if (state.run) throw new Error("行程尚未结束，请先完成或放下当前挑战。");
  const location = mapDesign.locations.find((l) => l.id === id);
  if (!location) throw new Error("找不到这处地点。");
  const issues = requirementIssues(game, location.requirements);
  if (issues.length) throw new Error(issues.join("；"));
  state.locationId = id;
  if (!state.visited.includes(id)) state.visited.push(id);
  for (const npcId of location.npcs) {
    const npc = game.npcs.find((n) => n.id === npcId);
    if (npc) npc.met = true;
  }
  const encounter = activities.find(
    (a) =>
      a.kind === "story" &&
      a.locationId === id &&
      !state.seenEncounters.includes(a.id) &&
      !activityIssues(game, a).length,
  );
  state.encounter = encounter?.id || null;
  if (encounter) state.seenEncounters.push(encounter.id);
}
export function interact(game: Game, npcId: string, topicId: string) {
  const companion = companions.find((c) => c.npcId === npcId),
    npc = game.npcs.find((n) => n.id === npcId);
  const topic = companion?.topics.find((t) => t.id === topicId);
  if (!companion || !npc || !topic) throw new Error("这段话题尚不可用。");
  if (game.adventure!.locationId !== companion.locationId)
    throw new Error("先到故人所在的地方拜访吧。");
  if (npc.favorability < topic.minFavorability) throw new Error("交情还未到这一步。");
  npc.met = true;
  const key = npcId + ":" + topicId;
  game.adventure!.conversations[key] =
    (game.adventure!.conversations[key] || 0) + 1;
  // 闲谈只有对白与记忆，不靠反复点聊天白拿属性或好感。
}
export function changeItem(game: Game, id: string) {
  const state = game.adventure!,
    item = items.find((i) => i.id === id);
  if (!item || !(state.inventory[id] > 0))
    throw new Error("行囊中没有这件东西。");
  if (item.kind === "equipment" && item.slot) {
    if (state.equipped[item.slot] === id) delete state.equipped[item.slot];
    else state.equipped[item.slot] = id;
  } else if (item.kind === "consumable" && item.use) {
    state.inventory[id]--;
    grantRewards(game, item.use);
  } else throw new Error("这是一件纪念信物，请好好收藏。");
}
export function purchase(game: Game, id: string) {
  const item = items.find((i) => i.id === id);
  if (!item?.price || item.price <= 0)
    throw new Error("此物不能购买，需要从挑战或故人处获得。");
  if (game.player.coins < item.price)
    throw new Error("银两不足，完成读书或委托可以赚取。");
  game.player.coins -= item.price;
  game.adventure!.inventory[id] = (game.adventure!.inventory[id] || 0) + 1;
}
export function claimRelationship(game: Game, npcId: string, index: number) {
  const companion = companions.find((c) => c.npcId === npcId),
    npc = game.npcs.find((n) => n.id === npcId),
    milestone = companion?.milestones[index];
  if (!npc || !milestone) throw new Error("这份心意尚不存在。");
  if (game.adventure!.locationId !== companion!.locationId)
    throw new Error("去当面领取这份心意吧。");
  const key = "bond:" + npcId + ":" + index;
  if (game.adventure!.rewardClaims.includes(key)) return;
  if (npc.favorability < milestone.favorability)
    throw new Error("还需要一些共同经历。");
  grantRewards(game, milestone.reward);
  game.adventure!.rewardClaims.push(key);
  game.journal.push({
    id: crypto.randomUUID(),
    day: game.records.length,
    kind: "milestone",
    title: milestone.title,
    text: npc.name + "：" + milestone.dialogue,
  });
}

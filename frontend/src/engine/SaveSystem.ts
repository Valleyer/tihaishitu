import { hydrateAdventure } from "./AdventureEngine";
import { hydrateLearning } from "./SpacedRepetitionEngine";
/**
 * 存档与题库写入前的结构检查。导入备份只接受当前版本格式。
 * 不直接覆盖旧人生；题库和人物引用完整后，交由本地 API 创建独立副本。
 */
import type { Bank, Game, NewGame, Question } from "../domain/types";
import {
  hydrateBankKnowledge,
  normalizeKnowledgePoint,
  normalizeQuestion,
} from "./QuestionBankManager";
import { chapterDesign, characterDesign, gameDesign } from "../content";
export function validateConfig(value: NewGame, banks: Bank[]): NewGame {
  if (
    !value ||
    typeof value.name !== "string" ||
    !value.name.trim() ||
    value.name.trim().length > 12
  )
    throw new Error("姓名请填写 1–12 个字。");
  if (
    !Array.isArray(value.bankIds) ||
    value.bankIds.some((id) => !banks.some((b) => b.id === id && b.enabled))
  )
    throw new Error("所用文集中包含已停用或不存在的项目。");
  if (
    !["slow", "normal"].includes(value.pace) ||
    !["gentle", "standard"].includes(value.difficulty)
  )
    throw new Error("开局设置无效。");
  const weights = value.weights || {};
  if (
    Object.values(weights).some((w) => !Number.isFinite(w) || w < 0 || w > 100)
  )
    throw new Error("科目权重应在 0–100 之间。");
  return {
    ...value,
    name: value.name.trim(),
    weights: { ...weights },
    bankIds: [...new Set(value.bankIds)],
  };
}
export function validateBank(bank: Bank): Bank {
  if (
    !bank ||
    typeof bank.id !== "string" ||
    !bank.id ||
    ["__proto__", "constructor", "prototype"].includes(bank.id) ||
    !bank.name?.trim()
  )
    throw new Error("题库名称或标识无效。");
  if (!Number.isFinite(bank.weight) || bank.weight < 0 || bank.weight > 100)
    throw new Error("题库权重须在 0–100 之间。");
  if (
    !Array.isArray(bank.questions) ||
    bank.questions.length > gameDesign.limits.questionsPerBank
  )
    throw new Error(
      "题库最多支持 " + gameDesign.limits.questionsPerBank + " 道题。",
    );
  const knowledgePoints = (bank.knowledgePoints || []).map(
    normalizeKnowledgePoint,
  );
  if (new Set(knowledgePoints.map((point) => point.id)).size !== knowledgePoints.length)
    throw new Error("知识点 id 不可重复。");
  const declaredPointIds = new Set(knowledgePoints.map((point) => point.id));
  for (const point of knowledgePoints) {
    if (point.parentId && !declaredPointIds.has(point.parentId))
      throw new Error(
        `知识点“${point.name}”引用了不存在的上位知识点“${point.parentId}”。`,
      );
    if (point.prerequisites.includes(point.id))
      throw new Error(`知识点“${point.name}”不能把自身设为前置知识点。`);
    const missing = point.prerequisites.find((id) => !declaredPointIds.has(id));
    if (missing)
      throw new Error(
        `知识点“${point.name}”引用了不存在的前置知识点“${missing}”。`,
      );
  }
  const questions = bank.questions.map(normalizeQuestion);
  if (new Set(questions.map((q) => q.id)).size !== questions.length)
    throw new Error("题目 id 不可重复。");
  const normalized = hydrateBankKnowledge({
    ...bank,
    name: bank.name.trim(),
    knowledgePoints,
    questions,
    enabled: Boolean(bank.enabled),
  });
  const pointIds = new Set(normalized.knowledgePoints.map((point) => point.id));
  for (const question of normalized.questions) {
    if (
      question.knowledgePointIds.length < 1 ||
      question.knowledgePointIds.length > 3 ||
      question.knowledgePointIds.some((id) => !pointIds.has(id))
    )
      throw new Error(
        `题目“${question.id}”须关联 1–3 个文集中已有的知识点。`,
      );
  }
  return normalized;
}
export interface Backup {
  format: "tihaishitu-v2";
  game: Game;
  banks: Bank[];
  snapshot: Question | null;
}
export function parseBackup(text: string): Backup {
  if (text.length > 20_000_000)
    throw new Error("存档文件超过 20 MB，请检查文件。");
  const data = JSON.parse(text.replace(/^\uFEFF/, "")) as Backup;
  if (
    data.format !== "tihaishitu-v2" ||
    !data.game ||
    !Array.isArray(data.banks)
  )
    throw new Error("不是本版本的存档文件。");
  const game = data.game;
  const finite = (value: unknown) =>
    typeof value === "number" && Number.isFinite(value) && value >= 0;
  if (
    game.version !== 2 ||
    !Array.isArray(game.records) ||
    !Array.isArray(game.npcs) ||
    !Array.isArray(game.journal) ||
    !Array.isArray(game.flags) ||
    !game.notes ||
    !game.learning ||
    !game.player ||
    !Number.isInteger(game.chapter) ||
    game.chapter < 0 ||
    game.chapter >= chapterDesign.length
  )
    throw new Error("存档结构不完整。");
  if (
    !finite(game.player.knowledge) ||
    !finite(game.player.coins) ||
    !finite(game.player.reputation) ||
    game.records.some(
      (r) =>
        !r.question ||
        typeof r.correct !== "boolean" ||
        !Number.isFinite(Date.parse(r.at)),
    )
  )
    throw new Error("存档学习记录或人物数据损坏。");
  hydrateAdventure(game);
  if (
    characterDesign.some(
      (character) =>
        !game.npcs.some(
          (n) => n.id === character.id && finite(n.favorability) && n.favorability <= 100,
        ),
    )
  )
    throw new Error("存档人物关系数据损坏。");
  if (
    Object.values(game.learning).some(
      (r) =>
        !finite(r.attempts) ||
        !finite(r.dueAt) ||
        !finite(r.lastIndex) ||
        !finite(r.wrong) ||
        !finite(r.streak) ||
        (r.errorRate !== undefined &&
          (!finite(r.errorRate) || r.errorRate > 100)) ||
        !Array.isArray(r.wrongAnswers),
    )
  )
    throw new Error("存档复习数据损坏。");
  hydrateLearning(game);
  data.banks = data.banks.map(validateBank);
  game.config = validateConfig(
    game.config,
    data.banks.map((bank) => ({ ...bank, enabled: true })),
  );
  game.records.forEach((r) => normalizeQuestion(r.question, 0));
  if (game.attempt) {
    if (
      !data.snapshot ||
      game.attempt.question?.id !== data.snapshot.id ||
      !game.attempt.id ||
      !game.attempt.scene?.text ||
      !game.npcs.some((n) => n.id === game.attempt!.scene.npcId)
    )
      throw new Error("存档中的当前课业损坏。");
    data.snapshot = normalizeQuestion(data.snapshot, 0);
    if (
      game.attempt.result &&
      ![true, false, null].includes(game.attempt.result.correct)
    )
      throw new Error("作答结果无效。");
  }
  const state = game.adventure!;
  if (
    !state.inventory ||
    !state.attributes ||
    !state.equipped ||
    !Array.isArray(state.rewardClaims) ||
    !Array.isArray(state.visited) ||
    !state.best ||
    !state.clears
  )
    throw new Error("探索存档结构不完整。");
  for (const values of [
    state.inventory,
    state.attributes,
    state.best,
    state.clears,
  ])
    if (Object.values(values).some((value) => !finite(value)))
      throw new Error("探索数值损坏。");
  const run = state.run;
  if (
    run &&
    (!run.definition?.tiers?.length ||
      !Number.isInteger(run.definition.rounds) ||
      run.definition.rounds < 1 ||
      !Number.isInteger(run.answered) ||
      run.answered < 0 ||
      run.answered > run.definition.rounds ||
      !Number.isInteger(run.correct) ||
      run.correct < 0 ||
      run.correct > run.answered ||
      !["active", "settled"].includes(run.status) ||
      !game.attempt)
  )
    throw new Error("挑战进度损坏。");
  if (run?.status === "active" && run.answered >= run.definition.rounds)
    throw new Error("挑战轮次与状态不符。");
  if (run?.status === "settled" && run.answered !== run.definition.rounds)
    throw new Error("挑战结算状态不符。");
  return data;
}

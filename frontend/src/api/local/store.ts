/**
 * 本地版 GameApi：浏览器 localStorage 是唯一持久化来源。
 * 每次操作读取独立数据，完成判题、成长、剧情结算后一次写回；失败时不提交半套状态。
 * snapshots 保存当前课卷的完整快照，题库修订不会改变已发出的题目和答案。
 * editedBankIds 表示浏览器手工覆盖项（包含删除标记）；未覆盖的内置题库跟随 JSON 配置。
 * 本地模式按浏览器源隔离，仅支持单标签页写入。
 */
import type {
  Bank,
  Bootstrap,
  Game,
  GameApi,
  Question,
} from "../../domain/types";
import { seedBanks } from "./questions";
import { assertAnswer, validateAnswer } from "../../engine/AnswerValidator";
import { drawQuestion } from "../../engine/QuestionEngine";
import { makeScene, initialNpcs } from "../../engine/StoryEngine";
import { settleProgress } from "../../engine/ProgressionSystem";
import { recordLearning } from "../../engine/SpacedRepetitionEngine";
import { applyChoice, pendingEvent } from "../../engine/EventEngine";
import {
  parseBackup,
  validateBank,
  validateConfig,
} from "../../engine/SaveSystem";
import { shuffleQuestion } from "../../engine/OptionShuffler";
import { gameDesign, chapterDesign } from "../../content";
export const STORAGE_KEY = "tihaishitu:world:v2";
interface Database {
  version: 2;
  choiceVersion?: number;
  editedBankIds?: string[];
  saves: Record<string, Game>;
  banks: Bank[];
  activeId: string | null;
  snapshots: Record<string, Question>;
}
export function createLocalApi(
  storage: Pick<Storage, "getItem" | "setItem">,
): GameApi {
  function read(): Database {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw)
      return {
        version: 2,
        choiceVersion: 1,
        editedBankIds: [],
        saves: {},
        banks: structuredClone(seedBanks),
        activeId: null,
        snapshots: {},
      };
    try {
      const data = JSON.parse(raw) as Database;
      if (
        data.version !== 2 ||
        !data.saves ||
        !Array.isArray(data.banks) ||
        !data.snapshots
      )
        throw new Error();
      if (!data.choiceVersion) {
        if (!storage.getItem("tihaishitu:before-choice-update"))
          storage.setItem("tihaishitu:before-choice-update", raw);
        for (const bank of data.banks)
          for (let index = 0; index < bank.questions.length; index++) {
            const question = bank.questions[index];
            if (
              !["single_choice", "multiple_choice", "true_false"].includes(
                question.type,
              )
            ) {
              const replacement = seedBanks
                .find((seed) => seed.id === bank.id)
                ?.questions.find((seed) => seed.id === question.id);
              if (replacement)
                bank.questions[index] = structuredClone(replacement);
              else question.enabled = false;
            }
          }
        for (const game of Object.values(data.saves))
          if (
            game.attempt &&
            !["single_choice", "multiple_choice", "true_false"].includes(
              game.attempt.question.type,
            )
          )
            nextAttempt(data, game);
        data.choiceVersion = 1;
        write(data);
      }
      // 旧数据库无法可靠判断哪些题库曾被手改，首次升级先保留；新数据库只保留明确编辑过的覆盖项。
      data.editedBankIds ??= data.banks.map((bank) => bank.id);
      const overrides = new Set(data.editedBankIds);
      data.banks = [
        ...structuredClone(seedBanks.filter((bank) => !overrides.has(bank.id))),
        ...data.banks.filter((bank) => overrides.has(bank.id)),
      ];
      // 早期判断题由界面生成按钮，没有存 options；升级时补齐，保留题目与标准答案。
      for (const bank of data.banks) for (const question of bank.questions) {
        if (question.type === "true_false") question.options = { true: "正确", false: "错误" };
      }
      for (const game of Object.values(data.saves)) {
        const question = game.attempt?.question;
        if (question?.type === "true_false" && Object.keys(question.options).length < 2) {
          const snapshot = data.snapshots[game.id];
          if (snapshot) {
            const full = shuffleQuestion({ ...snapshot, options: { true: "正确", false: "错误" } });
            data.snapshots[game.id] = full;
            question.options = { ...full.options };
          }
        }
      }
      return data;
    } catch {
      throw new Error(
        "本地存档无法读取；请先备份浏览器数据。系统没有覆盖原数据。",
      );
    }
  }
  function write(db: Database) {
    try {
      storage.setItem(STORAGE_KEY, JSON.stringify(db));
    } catch {
      throw new Error(
        "本地保存失败，可能是浏览器空间不足。请导出存档后清理空间；本次变更没有保存。",
      );
    }
  }
  function get(db: Database, id: string): Game {
    if (!Object.hasOwn(db.saves, id)) throw new Error("存档不存在。");
    return db.saves[id];
  }
  function persist(db: Database, game: Game) {
    game.updatedAt = new Date().toISOString();
    game.revision++;
    db.activeId = game.id;
    db.saves[game.id] = game;
    write(db);
    return structuredClone(game);
  }
  // 发卷时洗牌并冻结题目；重试答题或刷新页面不重复洗牌。
  function nextAttempt(db: Database, game: Game, review = false) {
    const drawn = drawQuestion(game, db.banks, review);
    const previous = game.records.findLast(
      (record) => record.question.id === drawn.id,
    );
    const full = shuffleQuestion(
      drawn,
      previous ? Object.keys(previous.question.options) : undefined,
    );
    const {
      answer: _answer,
      aliases: _aliases,
      keywords: _keywords,
      explanation: _explanation,
      ...question
    } = full;
    const scene = makeScene(
      game,
      full,
      review || (game.learning[full.id]?.wrong ?? 0) > 0,
    );
    game.npcs.find((n) => n.id === scene.npcId)!.met = true;
    game.attempt = {
      id: crypto.randomUUID(),
      question,
      scene,
      result: null,
      review: review || (game.learning[full.id]?.wrong ?? 0) > 0,
    };
    db.snapshots[game.id] = full;
  }
  return {
    async bootstrap(): Promise<Bootstrap> {
      const db = read();
      return {
        saves: Object.values(db.saves)
          .map((g) => ({
            id: g.id,
            name: g.player.name,
            title: g.player.title,
            total: g.records.length,
            updatedAt: g.updatedAt,
          }))
          .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
        banks: db.banks,
        activeId: db.activeId,
        legacyNotice: Boolean(storage.getItem("tihaishitu:save:v1")),
      };
    },
    async createGame(config) {
      const db = read();
      config = validateConfig(config, db.banks);
      const now = new Date().toISOString();
      const game: Game = {
        id: crypto.randomUUID(),
        version: 2,
        revision: 0,
        createdAt: now,
        updatedAt: now,
        config,
        player: {
          name: config.name,
          gender: config.gender,
          origin: config.origin,
          knowledge: 0,
          reputation: 0,
          coins:
            gameDesign.origins.find((origin) => origin.name === config.origin)
              ?.coins ?? 24,
          title: chapterDesign[0].playerTitle,
        },
        npcs: initialNpcs(),
        records: [],
        learning: {},
        journal: [
          {
            id: crypto.randomUUID(),
            day: 0,
            title: gameDesign.initialJournalTitle,
            text: gameDesign.initialJournal,
            kind: "story",
          },
        ],
        flags: [],
        attempt: null,
        event: null,
        notes: {},
        chapter: 0,
      };
      nextAttempt(db, game);
      return persist(db, game);
    },
    async getGame(id) {
      const db = read();
      const game = get(db, id);
      db.activeId = id;
      write(db);
      return structuredClone(game);
    },
    async deleteGame(id) {
      const db = read();
      get(db, id);
      delete db.saves[id];
      delete db.snapshots[id];
      if (db.activeId === id) db.activeId = null;
      write(db);
    },
    // attemptId 标识一次发卷；已判过的请求直接返回原结果，防止连点重复涨属性。
    async answer(id, input) {
      const db = read();
      const game = get(db, id);
      const attempt = game.attempt;
      if (!attempt || input.attemptId !== attempt.id)
        throw new Error("课卷已更新，请重新载入存档。");
      if (attempt.result && attempt.result.correct !== null)
        return structuredClone(game);
      const full = db.snapshots[id];
      if (!full) throw new Error("当前课卷快照缺失，请恢复备份。");
      if (game.event) throw new Error("请先回应眼前的际遇。");
      let answer = input.answer;
      let correct: boolean | null;
      if (attempt.result) {
        if (typeof input.selfAssessment !== "boolean")
          return structuredClone(game);
        answer = attempt.result.answer;
        correct = input.selfAssessment;
      } else {
        assertAnswer(full, answer);
        correct = validateAnswer(full, answer);
      }
      attempt.result = {
        correct,
        answer,
        standard: full.answer,
        explanation: full.explanation,
        aliases: full.aliases,
        story: "",
        changes: [],
      };
      if (correct === null) return persist(db, game);
      const now = new Date().toISOString();
      game.records.push({
        attemptId: attempt.id,
        question: full,
        answer,
        correct,
        at: now,
        review: attempt.review,
      });
      recordLearning(game, full, correct, answer, now);
      attempt.result.changes = settleProgress(
        game,
        correct,
        full.difficulty,
        full.frequency,
      );
      attempt.result.story = correct
        ? attempt.scene.success
        : attempt.scene.failure;
      // 宽和模式保留信任；常规模式仅在同题再次答错时按配置扣除信任。
      if (
        !correct &&
        game.config.difficulty === "standard" &&
        game.learning[full.id].wrong > 1
      ) {
        const npc = game.npcs.find((n) => n.id === attempt.scene.npcId)!;
        const before = npc.trust;
        npc.trust = Math.max(
          0,
          before - gameDesign.growth.trustLossOnRepeatedWrong,
        );
        if (before !== npc.trust)
          attempt.result.changes.push({
            label: npc.name + " · 信任",
            before,
            after: npc.trust,
          });
      }
      game.journal.push({
        id: crypto.randomUUID(),
        day: game.records.length,
        title: attempt.scene.title,
        text: attempt.result.story,
        kind: "story",
      });
      game.event = pendingEvent(game);
      return persist(db, game);
    },
    async next(id, attemptId, reviewOnly = false) {
      const db = read();
      const game = get(db, id);
      if (game.attempt && game.attempt.id !== attemptId)
        return structuredClone(game);
      if (game.event) throw new Error("请先回应眼前的际遇。");
      if (
        game.attempt &&
        (!game.attempt.result || game.attempt.result.correct === null)
      )
        throw new Error("请先完成当前课业。");
      nextAttempt(db, game, reviewOnly);
      return persist(db, game);
    },
    async choose(id, eventId, choiceId) {
      const db = read();
      const game = get(db, id);
      applyChoice(game, eventId, choiceId);
      return persist(db, game);
    },
    async saveNote(id, questionId, note) {
      const db = read();
      const game = get(db, id);
      if (note.length > gameDesign.limits.noteLength)
        throw new Error(
          "每题批注最多 " + gameDesign.limits.noteLength + " 字。",
        );
      game.notes[questionId] = note;
      return persist(db, game);
    },
    async configure(id, bankIds, weights) {
      const db = read();
      const game = get(db, id);
      game.config = validateConfig(
        { ...game.config, bankIds, weights },
        db.banks,
      );
      drawQuestion(game, db.banks);
      return persist(db, game);
    },
    // 入章确认单独存储，章节介绍不会在每次打开游戏时重复播放。
    async acknowledgeChapter(id, chapterId) {
      const db = read();
      const game = get(db, id);
      if (chapterDesign[game.chapter].id !== chapterId)
        throw new Error("章节已变化，请重新载入。");
      if (!game.flags.includes("chapter-intro:" + chapterId))
        game.flags.push("chapter-intro:" + chapterId);
      return persist(db, game);
    },
    async putBank(bank) {
      const db = read();
      bank = validateBank(bank);
      db.editedBankIds = [...new Set([...(db.editedBankIds || []), bank.id])];
      const index = db.banks.findIndex((b) => b.id === bank.id);
      if (index < 0) db.banks.push(bank);
      else db.banks[index] = bank;
      write(db);
      return bank;
    },
    async deleteBank(id) {
      const db = read();
      if (Object.values(db.saves).some((g) => g.config.bankIds.includes(id)))
        throw new Error("仍有存档选用此文集，请先在对应存档的行囊中取消选用。");
      db.editedBankIds = [...new Set([...(db.editedBankIds || []), id])];
      db.banks = db.banks.filter((b) => b.id !== id);
      write(db);
    },
    async exportSave(id) {
      const db = read();
      const game = get(db, id);
      return JSON.stringify(
        {
          format: "tihaishitu-v2",
          game,
          banks: db.banks.filter((b) => game.config.bankIds.includes(b.id)),
          snapshot: db.snapshots[id] || null,
        },
        null,
        2,
      );
    },
    async importSave(json) {
      const backup = parseBackup(json);
      const db = read();
      // 导入备份创建独立人生，并重映射题库 id；避免同名题库覆盖原存档的课卷。
      const remap = new Map<string, string>();
      for (const bank of backup.banks) {
        const id = crypto.randomUUID();
        remap.set(bank.id, id);
        db.banks.push({ ...bank, id });
        (db.editedBankIds ??= []).push(id);
      }
      const remapKey = (key: string) => {
        const [id, ...parts] = key.split("::");
        return (
          (remap.get(id) || id) + (parts.length ? "::" + parts.join("::") : "")
        );
      };
      const game = backup.game;
      game.id = crypto.randomUUID();
      game.config.bankIds = game.config.bankIds.map(
        (id) => remap.get(id) || id,
      );
      game.records.forEach((r) => {
        r.question.id = remapKey(r.question.id);
      });
      game.learning = Object.fromEntries(
        Object.entries(game.learning).map(([key, value]) => [
          remapKey(key),
          value,
        ]),
      );
      game.notes = Object.fromEntries(
        Object.entries(game.notes).map(([key, value]) => [
          remapKey(key),
          value,
        ]),
      );
      if (game.attempt)
        game.attempt.question.id = remapKey(game.attempt.question.id);
      if (backup.snapshot)
        db.snapshots[game.id] = {
          ...backup.snapshot,
          id: remapKey(backup.snapshot.id),
        };
      return persist(db, game);
    },
  };
}

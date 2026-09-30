import type { GameApi, Session } from "../../domain/types";
import { publicQuestion, questions } from "./questions";
export const STORAGE_KEY = "tihaishitu:save:v1";
interface RecordItem {
  questionId: string;
  correct: boolean;
  at: string;
}
interface Save {
  version: 1;
  index: number;
  session: Session;
  records: RecordItem[];
}
function initialSave(): Save {
  return {
    version: 1,
    index: 0,
    records: [],
    session: {
      id: "local-save",
      player: { name: "折叶", title: "寒门书生", knowledge: 0 },
      question: publicQuestion(questions[0]),
      attemptId: crypto.randomUUID(),
      result: null,
    },
  };
}
// Injected storage makes persistence and retries testable without a browser.
export function createLocalApi(
  storage: Pick<Storage, "getItem" | "setItem">,
): GameApi {
  function read(): Save {
    const raw = storage.getItem(STORAGE_KEY);
    if (!raw) return write(initialSave());
    try {
      const save = JSON.parse(raw) as Save;
      if (
        save.version !== 1 ||
        !Number.isInteger(save.index) ||
        !questions[save.index] ||
        !Array.isArray(save.records) ||
        !save.session?.player ||
        save.session.question?.id !== questions[save.index].id ||
        typeof save.session.attemptId !== "string" ||
        !Number.isFinite(save.session.player.knowledge)
      )
        throw new Error();
      return save;
    } catch {
      throw new Error(
        "本地存档无法读取。请先备份浏览器中的存档数据，再清理损坏的存档。",
      );
    }
  }
  function write(save: Save): Save {
    storage.setItem(STORAGE_KEY, JSON.stringify(save));
    return save;
  }
  return {
    async getSession() {
      return read().session;
    },
    async submitAnswer(input) {
      const save = read();
      if (
        input.attemptId !== save.session.attemptId ||
        input.questionId !== save.session.question.id
      )
        throw new Error("题目已更新，请刷新页面后重试。");
      if (save.session.result) return save.session;
      const question = questions[save.index];
      if (!question.options.some((option) => option.id === input.answer))
        throw new Error("请选择有效的答案。");
      const correct = input.answer === question.answer;
      const knowledgeGain = correct ? 3 : 1;
      save.session.result = {
        questionId: question.id,
        selected: input.answer,
        correctAnswer: question.answer,
        correct,
        explanation: question.explanation,
        knowledgeGain,
        story: correct
          ? "先生看过你的答卷，轻轻颔首：“此处已明，继续用功。”"
          : "先生在卷边留下一笔：“此处再思。”这道疑难，已收进你的旧案。",
      };
      save.session.player.knowledge += knowledgeGain;
      save.records.push({
        questionId: question.id,
        correct,
        at: new Date().toISOString(),
      });
      return write(save).session;
    },
    async nextQuestion(attemptId) {
      const save = read();
      if (attemptId !== save.session.attemptId) return save.session;
      if (!save.session.result) throw new Error("请先完成当前题目。");
      save.index = (save.index + 1) % questions.length;
      save.session.question = publicQuestion(questions[save.index]);
      save.session.result = null;
      save.session.attemptId = crypto.randomUUID();
      return write(save).session;
    },
    async getBanks() {
      return [
        {
          id: "demo",
          name: "书院入门卷",
          description: "数学一、408、英语一 · 4 道示例题循环演示",
          count: questions.length,
        },
      ];
    },
    async getMistakes() {
      const records = read().records;
      return questions.flatMap((question) => {
        const wrong = records.filter(
          (record) => record.questionId === question.id && !record.correct,
        );
        return wrong.length
          ? [
              {
                question: publicQuestion(question),
                wrongCount: wrong.length,
                lastWrongAt: wrong[wrong.length - 1].at,
                correctAnswer: question.answer,
                explanation: question.explanation,
              },
            ]
          : [];
      });
    },
    async getStatistics() {
      const records = read().records;
      let streak = 0;
      for (let i = records.length - 1; i >= 0 && records[i].correct; i--)
        streak++;
      const today = new Date().toLocaleDateString("en-CA");
      return {
        total: records.length,
        correct: records.filter((record) => record.correct).length,
        today: records.filter(
          (record) => new Date(record.at).toLocaleDateString("en-CA") === today,
        ).length,
        streak,
      };
    },
  };
}

/**
 * 题库导入与标准化：JSON、CSV 和界面编辑最后都转换为统一 Question。
 * 导入先完整解析再保存，发现非法题型或答案时整份拒绝，避免半份题库落库。
 * JSON 选择题使用 A–F 原始键；判断题自动生成 true/false 两个选项。
 */
import type { Bank, Question, QuestionType } from "../domain/types";
import { gameDesign } from "../content";
export const typeNames: Record<QuestionType, string> = {
  single_choice: "单选",
  multiple_choice: "多选",
  true_false: "判断",
};
function list(value: unknown): string[] {
  return Array.isArray(value)
    ? value.map(String)
    : typeof value === "string"
      ? value
          .split(/[|；;]/)
          .map((s) => s.trim())
          .filter(Boolean)
      : [];
}
function rating(value: unknown, fallback: number) {
  const number = value === undefined || value === "" ? fallback : Number(value);
  if (!Number.isInteger(number) || number < 1 || number > 5)
    throw new Error("难度与频率必须是 1–5 的整数");
  return number;
}
function bool(value: unknown, fallback: boolean) {
  if (value === undefined || value === "") return fallback;
  if ([true, "true", "TRUE", "1", 1, "正确", "对"].includes(value as string))
    return true;
  if ([false, "false", "FALSE", "0", 0, "错误", "错"].includes(value as string))
    return false;
  throw new Error("布尔值必须为 true / false");
}
export function normalizeQuestion(value: unknown, index: number): Question {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("题目必须为对象");
  const q = value as Record<string, unknown>;
  const type = String(q.type || "single_choice") as QuestionType;
  if (!(type in typeNames))
    throw new Error("仅支持判断、单选、多选，请将 " + type + " 改编后导入");
  const text = String(q.question || q.prompt || "").trim();
  if (!text || text.length > 6000)
    throw new Error("题干不能为空且不能超过 6000 字");
  const options: Record<string, string> = {};
  if (q.options && typeof q.options === "object") {
    if (Array.isArray(q.options))
      q.options.forEach((o) => {
        options[String(o.id)] = String(o.text);
      });
    else
      Object.entries(q.options).forEach(([key, val]) => {
        options[key] = String(val);
      });
  } else
    ["A", "B", "C", "D", "E", "F"].forEach((key) => {
      if (q["option" + key]) options[key] = String(q["option" + key]);
    });
  let answer = q.answer;
  if (type === "true_false") {
    if (answer === undefined || answer === "")
      throw new Error("判断题缺少答案");
    answer = bool(answer, false);
    for (const key of Object.keys(options)) delete options[key];
    options.true = "正确";
    options.false = "错误";
  } else if (type === "multiple_choice")
    answer = Array.isArray(answer)
      ? [...new Set(answer.map(String))]
      : String(answer ?? "")
          .replace(/[\s,，;；|]/g, "")
          .split("")
          .filter(Boolean);
  else answer = String(answer ?? "").trim();
  if (type.includes("choice")) {
    if (
      Object.keys(options).length < 2 ||
      Object.keys(options).length > 6 ||
      Object.keys(options).some((key) => !/^[A-F]$/.test(key)) ||
      Object.values(options).some((v) => !v.trim())
    )
      throw new Error("选择题需要 2–6 个非空选项，选项标识使用 A–F");
    const answers = Array.isArray(answer) ? answer : [String(answer)];
    if (!answers.length || answers.some((a) => !(a in options)))
      throw new Error("答案必须对应已有选项");
  } else if (typeof answer === "string" && !answer)
    throw new Error("标准答案不能为空");
  return {
    id: String(q.id || "question-" + (index + 1)),
    type,
    question: text,
    options,
    answer: answer as Question["answer"],
    subject: String(q.subject || "自修"),
    category: String(q.category || "通识"),
    chapter: String(q.chapter || "未分章"),
    aliases: list(q.aliases),
    keywords: list(q.keywords),
    explanation: String(q.explanation || "请对照标准答案复习。"),
    difficulty: rating(q.difficulty, 3),
    frequency: rating(q.frequency, 3),
    tags: list(q.tags),
    enabled: bool(q.enabled, true),
  };
}
// 手动逐字符解析引号状态，保留 CSV 单元格内部的逗号、换行和双引号。
export function parseCSV(text: string): Record<string, string>[] {
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  const source = text.replace(/^\uFEFF/, "");
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (c === '"') {
      if (quoted && source[i + 1] === '"') {
        cell += '"';
        i++;
      } else quoted = !quoted;
    } else if (c === "," && !quoted) {
      row.push(cell);
      cell = "";
    } else if ((c === "\n" || c === "\r") && !quoted) {
      if (c === "\r" && source[i + 1] === "\n") i++;
      row.push(cell);
      if (row.some(Boolean)) rows.push(row);
      row = [];
      cell = "";
    } else cell += c;
  }
  if (quoted) throw new Error("CSV 引号没有闭合");
  row.push(cell);
  if (row.some(Boolean)) rows.push(row);
  if (rows.length < 2) throw new Error("CSV 需要表头和至少一道题");
  const header = rows.shift()!.map((h) => h.trim());
  if (new Set(header).size !== header.length)
    throw new Error("CSV 表头不能重复");
  return rows.map((values, index) => {
    if (values.length !== header.length)
      throw new Error("CSV 第 " + (index + 2) + " 行列数与表头不符");
    return Object.fromEntries(header.map((h, i) => [h, values[i]]));
  });
}
export function parseBank(
  text: string,
  format: "json" | "csv",
  name: string,
): Bank {
  if (text.length > 5_000_000) throw new Error("单次导入请控制在 5 MB 内");
  const data =
    format === "csv" ? parseCSV(text) : JSON.parse(text.replace(/^\uFEFF/, ""));
  const rows = Array.isArray(data) ? data : data.questions;
  if (
    !Array.isArray(rows) ||
    !rows.length ||
    rows.length > gameDesign.limits.questionsPerBank
  )
    throw new Error(
      "题库需包含 1–" +
        gameDesign.limits.questionsPerBank +
        " 道题，可使用数组或 { questions: [] }",
    );
  const questions = rows.map((q, i) => {
    try {
      return normalizeQuestion(q, i);
    } catch (error) {
      throw new Error("第 " + (i + 1) + " 题：" + (error as Error).message);
    }
  });
  if (new Set(questions.map((q) => q.id)).size !== questions.length)
    throw new Error("题目 id 重复，请先修改后再导入");
  return {
    id: crypto.randomUUID(),
    name: name.trim() || data.name || "自编文集",
    description: "自定义题库",
    questions,
    enabled: true,
    weight: 1,
  };
}
export function exportCSV(bank: Bank) {
  const headers = [
    "id",
    "subject",
    "category",
    "chapter",
    "type",
    "difficulty",
    "frequency",
    "question",
    "optionA",
    "optionB",
    "optionC",
    "optionD",
    "optionE",
    "optionF",
    "answer",
    "explanation",
    "tags",
    "aliases",
    "keywords",
    "enabled",
  ];
  const escape = (s: unknown) =>
    '"' + String(s ?? "").replaceAll('"', '""') + '"';
  return (
    "\uFEFF" +
    [
      headers.join(","),
      ...bank.questions.map((q) =>
        headers
          .map((h) =>
            escape(
              h.startsWith("option")
                ? q.options[h.slice(6)]
                : Array.isArray(q[h as keyof Question])
                  ? (q[h as keyof Question] as string[]).join("|")
                  : q[h as keyof Question],
            ),
          )
          .join(","),
      ),
    ].join("\r\n")
  );
}
export const questionKey = (bankId: string, questionId: string) =>
  bankId + "::" + questionId;

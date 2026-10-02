/**
 * 三种客观题的判分规则：单选比较原始键，判断比较布尔值，多选比较无序去重集合。
 * 多选必须全部一致，不做部分给分；显示顺序由 OptionShuffler 单独处理。
 */
import type { Answer, Question } from "../domain/types";
export function validateAnswer(question: Question, answer: Answer): boolean {
  if (question.type === "multiple_choice") {
    const canonical = (value: Answer) =>
      [
        ...new Set(
          Array.isArray(value)
            ? value
            : String(value)
                .toUpperCase()
                .replace(/[\s,，;；|]/g, "")
                .split(""),
        ),
      ]
        .sort()
        .join("|");
    return canonical(answer) === canonical(question.answer);
  }
  return answer === question.answer;
}
export function assertAnswer(question: Question, answer: Answer) {
  if (question.type === "true_false" && typeof answer !== "boolean")
    throw new Error("请判断正误。");
  if (
    question.type === "single_choice" &&
    (typeof answer !== "string" || !(answer in question.options))
  )
    throw new Error("请先选择一个答案。");
  if (
    question.type === "multiple_choice" &&
    (!Array.isArray(answer) ||
      !answer.length ||
      answer.some((value) => !(value in question.options)))
  )
    throw new Error("请至少选择一个有效选项。");
}
export const answerText = (answer: Answer) =>
  typeof answer === "boolean"
    ? answer
      ? "正确"
      : "错误"
    : Array.isArray(answer)
      ? answer.join("、")
      : answer;

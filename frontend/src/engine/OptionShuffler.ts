import type { Answer, Question } from "../domain/types";
/** 每次出题重排选项顺序，保留原始键用于判题；屏幕 A/B/C 是当次显示编号。
 * 同一题再次抽到时，若随机顺序恰好相同，轮转一位以免卷面完全重复。
 * 刷新当前课卷不会重新洗牌，以保证正在作答的内容稳定。
 */
export function shuffleQuestion(
  question: Question,
  previousKeys?: string[],
  random = Math.random,
): Question {
  const keys = Object.keys(question.options);
  for (let i = keys.length - 1; i > 0; i--) {
    const j = Math.floor(random() * (i + 1));
    [keys[i], keys[j]] = [keys[j], keys[i]];
  }
  const previous = previousKeys || Object.keys(question.options);
  if (keys.length > 1 && keys.join("|") === previous.join("|"))
    keys.push(keys.shift()!);
  return {
    ...question,
    options: Object.fromEntries(
      keys.map((key) => [key, question.options[key]]),
    ),
  };
}
export function displayAnswer(
  answer: Answer,
  options: Record<string, string>,
): string {
  const keys = Object.keys(options),
    answers = Array.isArray(answer) ? answer : [String(answer)];
  return answers
    .map((key) => {
      const index = keys.indexOf(key);
      return index < 0
        ? key
        : String.fromCharCode(65 + index) + " · " + options[key];
    })
    .join("；");
}

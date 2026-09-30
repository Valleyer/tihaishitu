import type { Question } from "../../domain/types";
export interface SeedQuestion extends Question {
  answer: string;
  explanation: string;
}
// Demonstration data, not a complete exam preparation bank.
export const questions: SeedQuestion[] = [
  {
    id: "os-001",
    subject: "408",
    chapter: "操作系统 · 虚拟内存",
    type: "single_choice",
    prompt: "在请求分页系统中，页面大小增大后，哪一项通常会增加？",
    options: [
      { id: "A", text: "页表项数量" },
      { id: "B", text: "页内碎片" },
      { id: "C", text: "页号位数" },
      { id: "D", text: "缺页次数一定增加" },
    ],
    answer: "B",
    explanation:
      "页面越大，页表项通常越少，页内碎片通常越大；缺页次数不能仅由页面大小判断。",
  },
  {
    id: "math-001",
    subject: "数学一",
    chapter: "线性代数 · 特征值",
    type: "single_choice",
    prompt: "若矩阵 A 满足 A² = A，则 A 的特征值只能取哪些值？",
    options: [
      { id: "A", text: "−1 和 1" },
      { id: "B", text: "任意实数" },
      { id: "C", text: "0 和 1" },
      { id: "D", text: "只能取 1" },
    ],
    answer: "C",
    explanation: "由 A² = A 得特征值满足 λ² = λ，因此 λ 只能是 0 或 1。",
  },
  {
    id: "os-002",
    subject: "408",
    chapter: "操作系统 · 地址转换",
    type: "single_choice",
    prompt: "TLB 未命中，是否一定发生缺页？",
    options: [
      { id: "A", text: "是，必须从磁盘读取页面" },
      { id: "B", text: "否，页面可能已经在主存中" },
    ],
    answer: "B",
    explanation:
      "TLB 是页表项的高速缓存。未命中后查主存页表，只有目标页不在主存时才发生缺页。",
  },
  {
    id: "english-001",
    subject: "英语一",
    chapter: "词汇 · 熟词僻义",
    type: "single_choice",
    prompt: "“address a problem” 中，address 的意思是？",
    options: [
      { id: "A", text: "写下地址" },
      { id: "B", text: "处理、应对" },
      { id: "C", text: "邮寄" },
      { id: "D", text: "忽略" },
    ],
    answer: "B",
    explanation:
      "address 作动词可表示处理、应对问题，不能只记住“地址”这一名词义。",
  },
];
export function publicQuestion(question: SeedQuestion): Question {
  return {
    id: question.id,
    subject: question.subject,
    chapter: question.chapter,
    type: question.type,
    prompt: question.prompt,
    options: question.options,
  };
}

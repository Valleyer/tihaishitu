export interface Question {
  id: string;
  subject: string;
  chapter: string;
  type: "single_choice";
  prompt: string;
  options: { id: string; text: string }[];
}
export interface AnswerResult {
  questionId: string;
  selected: string;
  correctAnswer: string;
  correct: boolean;
  explanation: string;
  story: string;
  knowledgeGain: number;
}
export interface Session {
  id: string;
  player: { name: string; title: string; knowledge: number };
  question: Question;
  attemptId: string;
  result: AnswerResult | null;
}
export interface QuestionBank {
  id: string;
  name: string;
  description: string;
  count: number;
}
export interface Mistake {
  question: Question;
  wrongCount: number;
  lastWrongAt: string;
  correctAnswer: string;
  explanation: string;
}
export interface Statistics {
  total: number;
  correct: number;
  today: number;
  streak: number;
}
export interface SubmitAnswer {
  attemptId: string;
  questionId: string;
  answer: string;
}
// Pages depend only on this contract, never on a particular adapter.
export interface GameApi {
  getSession(): Promise<Session>;
  submitAnswer(input: SubmitAnswer): Promise<Session>;
  nextQuestion(attemptId: string): Promise<Session>;
  getBanks(): Promise<QuestionBank[]>;
  getMistakes(): Promise<Mistake[]>;
  getStatistics(): Promise<Statistics>;
}

export function QuestionStemImage({ src }: { src?: string | null }) {
  if (!src) return null;
  return <img className="question-stem-image" src={src} alt="题目配图" />;
}

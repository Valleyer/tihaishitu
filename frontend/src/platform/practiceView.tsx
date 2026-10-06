import { RichText } from "../components/RichText";

type AnswerDisplayProps = {
  standard: unknown;
  presentationType: string;
  options?: Record<string, string>;
};

export function formatObjectiveAnswer(
  standard: unknown,
  presentationType: string,
  options: Record<string, string> = {},
): string | undefined {
  if (presentationType === "true_false") {
    if (standard === true || standard === "true") return "正确";
    if (standard === false || standard === "false") return "错误";
  }
  if (presentationType === "single_choice" && typeof standard === "string") return standard;
  if (presentationType === "multiple_choice" && Array.isArray(standard)) {
    const order = Object.keys(options);
    return standard.map(String).sort((left, right) => {
      const leftIndex = order.indexOf(left);
      const rightIndex = order.indexOf(right);
      return (leftIndex < 0 ? Number.MAX_SAFE_INTEGER : leftIndex)
        - (rightIndex < 0 ? Number.MAX_SAFE_INTEGER : rightIndex);
    }).join("、");
  }
  return undefined;
}

export function AnswerDisplay({ standard, presentationType, options }: AnswerDisplayProps) {
  const objective = formatObjectiveAnswer(standard, presentationType, options);
  if (objective !== undefined) return <span className="practice-answer-value">{objective}</span>;
  if (typeof standard === "string") return <div className="practice-answer-rich"><RichText>{standard}</RichText></div>;
  if (Array.isArray(standard)) return <span className="practice-answer-value">{standard.map(String).join("、")}</span>;
  if (typeof standard === "boolean") return <span className="practice-answer-value">{standard ? "正确" : "错误"}</span>;
  return <code className="practice-answer-fallback">{JSON.stringify(standard)}</code>;
}

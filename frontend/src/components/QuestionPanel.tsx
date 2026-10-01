/**
 * 答题中央面板：题目换新时由父组件 key=attempt.id 重置本题选择。
 * 提交使用原始选项键，A/B/C 仅按当前洗牌顺序显示；判断题必须保留 false 有效值。
 * 小屏题干和选项分段展示，长选项可展开；所有分页共享同一份选择状态。
 */
import { useState, useEffect } from "react";
import type { Answer, Attempt } from "../domain/types";
import { typeNames } from "../engine/QuestionBankManager";
import { displayAnswer } from "../engine/OptionShuffler";
import { Modal } from "./Modal";
export function QuestionPanel({
  nextLabel = "继续此生 →",
  allowReview = true,
  attempt,
  busy,
  submit,
  next,
  note,
  showNote,
  eventPending,
  reviewOnly,
  setReview,
  onEvent,
}: {
  nextLabel?: string;
  allowReview?: boolean;
  attempt: Attempt;
  busy: boolean;
  submit: (answer: Answer) => void;
  next: () => void;
  note: string;
  showNote: () => void;
  eventPending: boolean;
  reviewOnly: boolean;
  setReview: (value: boolean) => void;
  onEvent: () => void;
}) {
  const [answer, setAnswer] = useState<Answer>(""),
    [recheck, setRecheck] = useState(false),
    [expanded, setExpanded] = useState<string | null>(null);
  const [textPage, setTextPage] = useState(0),
    [optionPage, setOptionPage] = useState(0);
  const [small, setSmall] = useState(
    window.innerWidth < 720 || window.innerHeight < 600,
  );
  useEffect(() => {
    const resize = () =>
      setSmall(window.innerWidth < 720 || window.innerHeight < 600);
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);
  const q = attempt.question,
    result = attempt.result,
    ready =
      typeof answer === "boolean" ||
      (Array.isArray(answer) ? answer.length > 0 : !!answer);
  const chunkSize = small ? 100 : 220,
    chars = Array.from(q.question),
    pages = Math.max(1, Math.ceil(chars.length / chunkSize));
  const entries = Object.entries(q.options),
    perPage = small ? 3 : 6,
    optionPages = Math.ceil(entries.length / perPage);
  const resultView = !!result && !recheck;
  return (
    <article className={"scroll-paper " + (resultView ? "show-result" : "")}>
      <div className="paper-heading">
        <span>
          {attempt.scene.task} <b>·</b> {typeNames[q.type]}
        </span>
        <div>
          <span className="subject-tag">{q.subject}</span>
          {attempt.review && <span className="review-tag">旧案重审</span>}
        </div>
      </div>
      {resultView ? (
        <section
          className={"result-stage " + (result!.correct ? "good" : "wrong")}
          aria-live="polite"
        >
          <div className="result-seal">{result!.correct ? "可" : "思"}</div>
          <div className="result-heading">
            <span>{result!.correct ? "此卷已明" : "留待复核"}</span>
            <small>
              {result!.correct
                ? "一页读通，前路又明一分。"
                : "错处留卷，来日再审。"}
            </small>
          </div>
          <div className="answer-summary">
            <p>
              <b>标准答案</b>
              {displayAnswer(result!.standard, q.options)}
            </p>
            {!result!.correct && (
              <p>
                <b>你的回答</b>
                {displayAnswer(result!.answer, q.options)}
              </p>
            )}
          </div>
          <div className="explanation-block">
            <p>
              {result!.explanation.length > 160
                ? result!.explanation.slice(0, 160) + "…"
                : result!.explanation}
            </p>
            {result!.explanation.length > 160 && (
              <button
                className="text-button"
                onClick={() => setExpanded(result!.explanation)}
              >
                查看完整解析
              </button>
            )}
          </div>
          <div className="changes">
            {result!.changes.map((change) => (
              <span key={change.label}>
                {change.label}
                <b>
                  {change.before} → {change.after}
                </b>
              </span>
            ))}
          </div>
          <button className="text-button" onClick={() => setRecheck(true)}>
            重看题面与选项
          </button>
        </section>
      ) : (
        <div className="question-body">
          <p className="question-chapter">
            {q.chapter}
            <span>
              {"◆".repeat(q.frequency)} {q.tags.join(" · ")}
            </span>
          </p>
          <div className="question-prompt-area">
            <h2 className="question-text">
              {chars
                .slice(
                  Math.min(textPage, pages - 1) * chunkSize,
                  (Math.min(textPage, pages - 1) + 1) * chunkSize,
                )
                .join("")}
            </h2>
            {pages > 1 && (
              <div className="pager">
                <button
                  disabled={textPage === 0}
                  onClick={() => setTextPage((p) => p - 1)}
                >
                  上一段
                </button>
                <span>
                  题干 {textPage + 1}/{pages}
                </span>
                <button
                  disabled={textPage >= pages - 1}
                  onClick={() => setTextPage((p) => p + 1)}
                >
                  下一段
                </button>
              </div>
            )}
          </div>
          <fieldset
            className={"answers " + (entries.length > 2 ? "two-columns" : "")}
          >
            <legend className="sr-only">作答选项</legend>
            {entries
              .slice(optionPage * perPage, (optionPage + 1) * perPage)
              .map(([key, text], index) => {
                const current = result?.answer ?? answer,
                  checked = Array.isArray(current)
                    ? current.includes(key)
                    : String(current) === key,
                  right =
                    result &&
                    (Array.isArray(result.standard)
                      ? result.standard.includes(key)
                      : String(result.standard) === key);
                const label = String.fromCharCode(
                  65 + optionPage * perPage + index,
                );
                return (
                  <label
                    key={key}
                    className={
                      "answer-option " +
                      (checked ? "selected " : "") +
                      (right ? "right-option" : "")
                    }
                  >
                    <input
                      disabled={busy || !!result}
                      type={q.type === "multiple_choice" ? "checkbox" : "radio"}
                      name="answer"
                      checked={checked}
                      onChange={() => {
                        if (q.type === "multiple_choice") {
                          const values = Array.isArray(answer) ? answer : [];
                          setAnswer(
                            values.includes(key)
                              ? values.filter((v) => v !== key)
                              : [...values, key],
                          );
                        } else
                          setAnswer(
                            q.type === "true_false" ? key === "true" : key,
                          );
                      }}
                    />
                    <span className="answer-letter">{label}</span>
                    <span>
                      {text.length > (small ? 45 : 90)
                        ? text.slice(0, small ? 45 : 90) + "…"
                        : text}
                    </span>
                    {text.length > (small ? 45 : 90) && (
                      <button
                        type="button"
                        className="option-expand"
                        aria-label={"展开选项 " + label}
                        onClick={(e) => {
                          e.preventDefault();
                          setExpanded(label + " · " + text);
                        }}
                      >
                        展开
                      </button>
                    )}
                    {right && <span className="answer-mark">✓</span>}
                  </label>
                );
              })}
          </fieldset>
          {optionPages > 1 && (
            <div className="pager">
              <button
                disabled={optionPage === 0}
                onClick={() => setOptionPage((p) => p - 1)}
              >
                前组选项
              </button>
              <span>
                选项 {optionPage + 1}/{optionPages}
                {Array.isArray(answer) && answer.length
                  ? " · 已选 " + answer.length + " 项"
                  : ""}
              </span>
              <button
                disabled={optionPage >= optionPages - 1}
                onClick={() => setOptionPage((p) => p + 1)}
              >
                后组选项
              </button>
            </div>
          )}
          <p className="choice-hint">
            {q.type === "multiple_choice"
              ? "多选题 · 请选择全部符合条件的选项"
              : q.type === "true_false"
                ? "判断题 · 辨明此言真伪"
                : "单选题 · 请选择一个最合适的答案"}
          </p>
        </div>
      )}
      <div className="paper-footer">
        <div>
          <button className="text-button" onClick={showNote}>
            ✎ {note ? "查看批注" : "卷边批注"}
          </button>
          {allowReview && (
            <label className="review-switch">
              <input
                type="checkbox"
                checked={reviewOnly}
                onChange={(e) => setReview(e.target.checked)}
              />
              只重审旧案
            </label>
          )}
        </div>
        {result ? (
          <button
            className="ink-button"
            disabled={busy}
            onClick={
              eventPending ? onEvent : recheck ? () => setRecheck(false) : next
            }
          >
            {eventPending ? "回应眼前际遇 →" : recheck ? "返回批卷" : nextLabel}
          </button>
        ) : (
          <button
            className="ink-button"
            disabled={busy || !ready}
            onClick={() => submit(answer)}
          >
            落笔呈卷 →
          </button>
        )}
      </div>
      {expanded && (
        <Modal title="展开卷文" close={() => setExpanded(null)}>
          <p className="expanded-text">{expanded}</p>
        </Modal>
      )}
    </article>
  );
}

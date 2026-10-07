/**
 * 答题中央面板：题目换新时由父组件 key=attempt.id 重置本题选择。
 * 提交使用原始选项键，A/B/C 仅按当前洗牌顺序显示；判断题必须保留 false 有效值。
 * 小屏题干和选项分段展示，长选项可展开；所有分页共享同一份选择状态。
 */
import { useState, useEffect } from "react";
import type { Answer, Assessment, Attempt } from "../domain/types";
import { typeNames } from "../engine/QuestionBankManager";
import { examMetadataView } from "../utils/examMetadata";
import { Modal } from "./Modal";
import { RichText } from "./RichText";
export function QuestionPanel({
  nextLabel = "继续此生 →",
  allowReview = true,
  attempt,
  busy,
  submit,
  reveal,
  assess,
  noIdea = () => {},
  report = async () => {},
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
  reveal: () => void;
  assess: (assessment: Assessment) => void;
  noIdea?: () => void;
  report?: (reason: string, comment: string) => Promise<void>;
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
  const [reportOpen, setReportOpen] = useState(false),
    [reportReason, setReportReason] = useState("content_error"),
    [reportComment, setReportComment] = useState(""),
    [reportStatus, setReportStatus] = useState("");
  const [optionPage, setOptionPage] = useState(0);
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
    isSelfAssessment =
      q.gradingMode === "self_assessment" || q.type === "self_assessment",
    ready =
      typeof answer === "boolean" ||
      (Array.isArray(answer) ? answer.length > 0 : !!answer);
  const entries = Object.entries(q.options),
    perPage = small ? 3 : 6,
    optionPages = Math.ceil(entries.length / perPage);
  const resultView = !!result && !recheck;
  const assessmentTitle =
    result?.assessment === "partial"
      ? "部分明白"
      : result?.correct
        ? "此卷已明"
        : "留待复核";
  const knowledgePoints = attempt.reveal?.knowledgePoints || q.knowledgePoints;
  /**
   * 题面来源 metadata（后端在发题时冻结进 attempt snapshot，Hub 与 World 共用同一结构）。
   * 来源 / 真题标签 / 显示题号作为明确 metadata 展示，不塞进剧情文字；
   * role 只决定标签颜色（core 紫 / auxiliary 青），两种角色都显示。
   */
  const exam = examMetadataView(q.examMetadata);
  const roleByPointId = new Map(
    (exam?.knowledgePoints ?? []).map((tag) => [tag.id, tag.role]),
  );
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
          className={
            "result-stage " +
            (result!.assessment === "partial"
              ? "partial"
              : result!.correct
                ? "good"
                : "wrong")
          }
          aria-live="polite"
        >
          <div className="result-seal">{result!.correct ? "可" : "思"}</div>
          <div className="result-heading">
            <span>{assessmentTitle}</span>
            <small>
              {result!.assessment === "partial"
                ? "思路已有根基，补全步骤后再试。"
                : result!.correct
                ? "一页读通，前路又明一分。"
                : "错处留卷，来日再审。"}
            </small>
          </div>
          <div className="answer-summary">
            {!isSelfAssessment && <p>
              <b>标准答案</b>
              <RichAnswer answer={result!.standard!} options={q.options} trueFalse={q.type === "true_false"} />
            </p>}
            {!isSelfAssessment && !result!.correct && !result!.noIdea && (
              <p>
                <b>你的回答</b>
                <RichAnswer answer={result!.answer} options={q.options} trueFalse={q.type === "true_false"} />
              </p>
            )}
          </div>
          <div className="explanation-block">
            {isSelfAssessment && <h3>完整解析</h3>}
            <RichText>{result!.explanation}</RichText>
          </div>
          <details className="knowledge-explanation">
            <summary>查看本题知识点解析</summary>
            {knowledgePoints.map((point) => (
              <section key={point.id}>
                <h3>{point.name}</h3>
                <RichText>{point.explanation || point.description}</RichText>
              </section>
            ))}
          </details>
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
          <div className="knowledge-ribbon">
            <span>本题考查</span>
            {q.knowledgePoints.map((point) => (
              <button
                type="button"
                key={point.id}
                className={
                  "knowledge-tag " + (roleByPointId.get(point.id) ?? "core")
                }
                onClick={() =>
                  setExpanded(
                    `### ${point.name}\n\n${point.explanation || point.description}`,
                  )
                }
              >
                {point.name}
              </button>
            ))}
            {exam?.examLabel && <span className="exam-label">{exam.examLabel}</span>}
            {exam?.displayQuestionNumber && <span className="exam-question-number">第{exam.displayQuestionNumber}题</span>}
            {exam?.sourceName && <span className="exam-source">{exam.sourceName}</span>}
          </div>
          <div className="question-prompt-area">
            <RichText className="question-text">{q.question}</RichText>
          </div>
          {isSelfAssessment ? (
            attempt.reveal ? (
              <section className="self-assessment-reference" aria-live="polite">
                <h3>参考解析</h3>
                <RichText>{attempt.reveal.explanation}</RichText>
                <details className="knowledge-explanation">
                  <summary>查看知识点解析</summary>
                  {attempt.reveal.knowledgePoints.map((point) => (
                    <section key={point.id}>
                      <h3>{point.name}</h3>
                      <RichText>{point.explanation || point.description}</RichText>
                    </section>
                  ))}
                </details>
              </section>
            ) : (
              <p className="choice-hint self-assessment-hint">
                请先在纸上完成推导或作答，再查看参考解析并如实自评。
              </p>
            )
          ) : (
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
                    <RichText inline>{text}</RichText>
                    {right && <span className="answer-mark">✓</span>}
                  </label>
                );
              })}
          </fieldset>
          )}
          {!isSelfAssessment && optionPages > 1 && (
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
          {!isSelfAssessment && <p className="choice-hint">
            {q.type === "multiple_choice"
              ? "多选题 · 请选择全部符合条件的选项"
              : q.type === "true_false"
                ? "判断题 · 辨明此言真伪"
                : "单选题 · 请选择一个最合适的答案"}
          </p>}
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
          <button className="text-button question-report-trigger" onClick={() => setReportOpen(true)}>
            题目有误？
          </button>
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
        ) : isSelfAssessment ? (
          attempt.reveal ? (
            <div className="self-assessment-actions" aria-label="自评结果">
              <button disabled={busy} onClick={noIdea}>我没思路</button>
              <button disabled={busy} onClick={() => assess("correct")}>
                完整答对
              </button>
              <button disabled={busy} onClick={() => assess("partial")}>
                部分正确
              </button>
              <button disabled={busy} onClick={() => assess("wrong")}>
                做错／不会
              </button>
            </div>
          ) : (
            <div className="answer-actions"><button disabled={busy} onClick={noIdea}>我没思路</button>
              <button className="ink-button" disabled={busy} onClick={reveal}>我已完成，查看参考解析</button></div>
          )
        ) : (
          <div className="answer-actions"><button disabled={busy} onClick={noIdea}>我没思路</button>
            <button className="ink-button" disabled={busy || !ready} onClick={() => submit(answer)}>落笔呈卷 →</button></div>
        )}
      </div>
      {expanded && (
        <Modal title="展开卷文" close={() => setExpanded(null)}>
          <RichText className="expanded-text">{expanded}</RichText>
        </Modal>
      )}
      {reportOpen && (
        <Modal title="题目有误？" subtitle="反馈不会影响本次作答" close={() => setReportOpen(false)}>
          <form className="question-report-form" onSubmit={(event) => {
            event.preventDefault(); setReportStatus("提交中…");
            void report(reportReason, reportComment).then(() => setReportStatus("已收到反馈"))
              .catch((error: Error) => setReportStatus(error.message));
          }}>
            <label>问题类型<select value={reportReason} onChange={(event) => setReportReason(event.target.value)}>
              <option value="content_error">题干有误</option><option value="answer_error">答案有误</option>
              <option value="analysis_error">解析有误</option><option value="format_error">排版有误</option>
              <option value="other">其他</option>
            </select></label>
            <label>补充说明（可空）<textarea maxLength={1000} value={reportComment}
              onChange={(event) => setReportComment(event.target.value)} /></label>
            <button className="ink-button" disabled={reportStatus === "提交中…" || reportStatus === "已收到反馈"}>提交</button>
            {reportStatus && <p role="status">{reportStatus}</p>}
          </form>
        </Modal>
      )}
    </article>
  );
}

function RichAnswer({ answer, options, trueFalse }: { answer: Answer; options: Record<string,string>; trueFalse: boolean }) {
  const keys = Array.isArray(answer) ? answer : [String(answer)];
  return <span className="rich-answer">{keys.map((key, index) => {
    const position = Object.keys(options).indexOf(key);
    const label = trueFalse ? "" : position < 0 ? key : String.fromCharCode(65 + position) + ". ";
    return <span key={key}>{index > 0 && "；"}{label}<RichText inline>{options[key] ?? key}</RichText></span>;
  })}</span>;
}

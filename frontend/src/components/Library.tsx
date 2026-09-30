/**
 * 藏书阁管理界面。编辑后调用统一题库校验；写入成功才退出编辑。
 * 浏览器修改优先于内置配置；已发出的题面由快照保护，新内容在下一次抽到时生效。
 */
import { useState } from "react";
import type { Bank, Question, QuestionType } from "../domain/types";
import {
  exportCSV,
  normalizeQuestion,
  parseBank,
  typeNames,
} from "../engine/QuestionBankManager";
import { download } from "../utils/download";
export function Library({
  banks,
  busy,
  save,
  remove,
  report,
}: {
  banks: Bank[];
  busy: boolean;
  save: (bank: Bank) => Promise<void>;
  remove: (id: string) => Promise<void>;
  report: (text: string) => void;
}) {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState(banks[0]?.id || "");
  const bank = banks.find((b) => b.id === selected);
  const [query, setQuery] = useState(""),
    [subject, setSubject] = useState(""),
    [tag, setTag] = useState(""),
    [chapter, setChapter] = useState("");
  const [checked, setChecked] = useState<string[]>([]);
  const [editing, setEditing] = useState<Question | null>(null);
  const [isNew, setIsNew] = useState(false);
  const [importing, setImporting] = useState(false),
    [importText, setImportText] = useState(""),
    [format, setFormat] = useState<"json" | "csv">("json"),
    [name, setName] = useState("自编文集");
  const subjects = [...new Set(bank?.questions.map((q) => q.subject) || [])],
    tags = [...new Set(bank?.questions.flatMap((q) => q.tags) || [])],
    chapters = [...new Set(bank?.questions.map((q) => q.chapter) || [])];
  const questions =
    bank?.questions.filter(
      (q) =>
        (!query ||
          (q.question + q.id).toLowerCase().includes(query.toLowerCase())) &&
        (!subject || q.subject === subject) &&
        (!tag || q.tags.includes(tag)) &&
        (!chapter || q.chapter === chapter),
    ) || [];
  const currentPage = Math.min(
    page,
    Math.max(0, Math.ceil(questions.length / 5) - 1),
  );
  async function importBank() {
    try {
      const next = parseBank(importText, format, name);
      await save(next);
      setSelected(next.id);
      setImporting(false);
      setImportText("");
    } catch (error) {
      report((error as Error).message);
    }
  }
  async function saveQuestion() {
    if (!editing || !bank) return;
    try {
      const q = normalizeQuestion(editing, 0);
      if (isNew && bank.questions.some((old) => old.id === q.id))
        throw new Error("题目标识已存在");
      await save({
        ...bank,
        questions: isNew
          ? [...bank.questions, q]
          : bank.questions.map((old) => (old.id === q.id ? q : old)),
      });
      setEditing(null);
    } catch (error) {
      report((error as Error).message);
    }
  }
  const fresh = () =>
    normalizeQuestion(
      {
        id: crypto.randomUUID(),
        subject: "自修",
        chapter: "第一卷",
        type: "single_choice",
        question: "请填写题干",
        options: { A: "选项一", B: "选项二" },
        answer: "A",
      },
      0,
    );
  if (editing)
    return (
      <div className="question-editor">
        <button className="text-button" onClick={() => setEditing(null)}>
          ← 返回藏书阁
        </button>
        <h3>{isNew ? "添一页新卷" : "修订课卷"}</h3>
        <div className="form-grid">
          <label>
            题型
            <select
              value={editing.type}
              onChange={(e) =>
                setEditing({
                  ...editing,
                  type: e.target.value as QuestionType,
                  answer:
                    e.target.value === "true_false"
                      ? true
                      : e.target.value === "multiple_choice"
                        ? ["A"]
                        : "A",
                })
              }
            >
              {Object.entries(typeNames).map(([key, label]) => (
                <option key={key} value={key}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label>
            科目
            <input
              value={editing.subject}
              onChange={(e) =>
                setEditing({ ...editing, subject: e.target.value })
              }
            />
          </label>
          <label>
            章节
            <input
              value={editing.chapter}
              onChange={(e) =>
                setEditing({ ...editing, chapter: e.target.value })
              }
            />
          </label>
          <label>
            分类
            <input
              value={editing.category}
              onChange={(e) =>
                setEditing({ ...editing, category: e.target.value })
              }
            />
          </label>
          <label>
            难度（1–5）
            <input
              type="number"
              min={1}
              max={5}
              value={editing.difficulty}
              onChange={(e) =>
                setEditing({ ...editing, difficulty: Number(e.target.value) })
              }
            />
          </label>
          <label>
            频率（1–5）
            <input
              type="number"
              min={1}
              max={5}
              value={editing.frequency}
              onChange={(e) =>
                setEditing({ ...editing, frequency: Number(e.target.value) })
              }
            />
          </label>
        </div>
        <label>
          题干
          <textarea
            value={editing.question}
            onChange={(e) =>
              setEditing({ ...editing, question: e.target.value })
            }
          />
        </label>
        {editing.type.includes("choice") && (
          <div className="form-grid">
            {["A", "B", "C", "D", "E", "F"].map((key) => (
              <label key={key}>
                选项 {key}
                <input
                  value={editing.options[key] || ""}
                  onChange={(e) => {
                    const options = { ...editing.options };
                    if (e.target.value) options[key] = e.target.value;
                    else delete options[key];
                    setEditing({ ...editing, options });
                  }}
                />
              </label>
            ))}
          </div>
        )}
        <label>
          标准答案
          {editing.type === "true_false" ? (
            <select
              value={String(editing.answer)}
              onChange={(e) =>
                setEditing({ ...editing, answer: e.target.value === "true" })
              }
            >
              <option value="true">正确</option>
              <option value="false">错误</option>
            </select>
          ) : (
            <input
              value={
                Array.isArray(editing.answer)
                  ? editing.answer.join("|")
                  : String(editing.answer)
              }
              onChange={(e) =>
                setEditing({
                  ...editing,
                  answer:
                    editing.type === "multiple_choice"
                      ? e.target.value.split(/[|,，]/).filter(Boolean)
                      : e.target.value,
                })
              }
              placeholder={
                editing.type === "multiple_choice"
                  ? "多个选项用 | 分隔，如 A|C"
                  : "填写标准答案"
              }
            />
          )}
        </label>
        <label>
          简短解析
          <textarea
            value={editing.explanation}
            onChange={(e) =>
              setEditing({ ...editing, explanation: e.target.value })
            }
          />
        </label>
        <div className="form-grid">
          <label>
            标签（分号分隔）
            <input
              value={editing.tags.join(";")}
              onChange={(e) =>
                setEditing({ ...editing, tags: e.target.value.split(";") })
              }
            />
          </label>
        </div>
        <button
          className="gold-button"
          disabled={busy}
          onClick={() => void saveQuestion()}
        >
          收录此页
        </button>
      </div>
    );
  return (
    <div className="library">
      <div className="toolbar">
        <select
          aria-label="选择文集"
          value={selected}
          onChange={(e) => {
            setSelected(e.target.value);
            setChecked([]);
            setSubject("");
            setTag("");
            setChapter("");
          }}
        >
          {banks.map((b) => (
            <option key={b.id} value={b.id}>
              {b.name}（{b.questions.length}题）
            </option>
          ))}
        </select>
        <button
          onClick={() => {
            setImporting(!importing);
          }}
        >
          导入 JSON / CSV
        </button>
        <button
          disabled={busy}
          onClick={async () => {
            const next: Bank = {
              id: crypto.randomUUID(),
              name: "未命名文集",
              description: "自编课卷",
              questions: [],
              enabled: true,
              weight: 1,
            };
            try {
              await save(next);
              setSelected(next.id);
            } catch {
              /* 上层统一展示错误，保留编辑内容。 */
            }
          }}
        >
          新建文集
        </button>
      </div>
      {importing && (
        <section className="inset">
          <h3>收录外来书卷</h3>
          <div className="form-grid">
            <label>
              文集名称
              <input value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <label>
              格式
              <select
                value={format}
                onChange={(e) => setFormat(e.target.value as "json" | "csv")}
              >
                <option value="json">JSON</option>
                <option value="csv">CSV</option>
              </select>
            </label>
          </div>
          <label className="file-input">
            选择题库文件
            <input
              type="file"
              accept=".json,.csv"
              onChange={async (e) => {
                const file = e.target.files?.[0];
                if (file) {
                  if (file.size > 5_000_000) {
                    report("文件超过 5 MB");
                    return;
                  }
                  setFormat(
                    file.name.toLowerCase().endsWith(".csv") ? "csv" : "json",
                  );
                  setImportText(await file.text());
                  setName(file.name.replace(/\.(csv|json)$/i, ""));
                }
              }}
            />
          </label>
          <textarea
            aria-label="题库导入内容"
            rows={6}
            value={importText}
            onChange={(e) => setImportText(e.target.value)}
            placeholder="选择文件或粘贴题库内容。支持设计文档中的题目格式。"
          />
          <div className="toolbar">
            <button
              disabled={busy || !importText}
              onClick={() => void importBank()}
            >
              校验并收录
            </button>
            <button
              onClick={() =>
                download(
                  "题库示例.json",
                  JSON.stringify({ questions: [fresh()] }, null, 2),
                )
              }
            >
              下载格式示例
            </button>
          </div>
          <p className="hint">导入先整体校验，有错误时不会写入半份题库。</p>
        </section>
      )}
      {bank && !importing && (
        <>
          <div className="bank-settings">
            <label>
              文集名称
              <input
                key={bank.id + "name"}
                defaultValue={bank.name}
                onBlur={(e) => {
                  if (e.target.value !== bank.name)
                    void save({ ...bank, name: e.target.value }).catch(
                      () => {},
                    );
                }}
              />
            </label>
            <label>
              抽取权重
              <input
                type="number"
                key={bank.id + "weight"}
                min={0}
                max={100}
                defaultValue={bank.weight}
                onBlur={(e) => {
                  if (Number(e.target.value) !== bank.weight)
                    void save({
                      ...bank,
                      weight: Number(e.target.value),
                    }).catch(() => {});
                }}
              />
            </label>
            <label className="inline-check">
              <input
                type="checkbox"
                checked={bank.enabled}
                disabled={busy}
                onChange={(e) =>
                  void save({ ...bank, enabled: e.target.checked }).catch(
                    () => {},
                  )
                }
              />
              启用文集
            </label>
          </div>
          <div className="toolbar">
            <input
              aria-label="搜索题目"
              placeholder="查找题干或编号…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <select
              aria-label="按科目筛选"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
            >
              <option value="">所有科目</option>
              {subjects.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
            <select
              aria-label="按章节筛选"
              value={chapter}
              onChange={(e) => setChapter(e.target.value)}
            >
              <option value="">所有章节</option>
              {chapters.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
            <select
              aria-label="按标签筛选"
              value={tag}
              onChange={(e) => setTag(e.target.value)}
            >
              <option value="">所有标签</option>
              {tags.map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </div>
          <div className="toolbar compact">
            <button
              onClick={() => {
                setEditing(fresh());
                setIsNew(true);
              }}
            >
              ＋ 添题
            </button>
            <button
              onClick={() =>
                download(bank.name + ".json", JSON.stringify(bank, null, 2))
              }
            >
              导出 JSON
            </button>
            <button
              onClick={() =>
                download(
                  bank.name + ".csv",
                  exportCSV(bank),
                  "text/csv;charset=utf-8",
                )
              }
            >
              导出 CSV
            </button>
            {[true, false].map((enabled) => (
              <button
                key={String(enabled)}
                disabled={busy || !checked.length}
                onClick={() =>
                  void save({
                    ...bank,
                    questions: bank.questions.map((q) =>
                      checked.includes(q.id) ? { ...q, enabled } : q,
                    ),
                  }).catch(() => {})
                }
              >
                {enabled ? "启用" : "停用"}选中
              </button>
            ))}
            <button
              className="danger"
              disabled={busy}
              onClick={() => {
                if (
                  window.confirm(
                    "删除“" + bank.name + "”？仍被存档选用时会阻止删除。",
                  )
                )
                  void remove(bank.id).catch(() => {});
              }}
            >
              删除文集
            </button>
          </div>
          <p className="hint">
            共 {questions.length} 题 · 已选 {checked.length}{" "}
            题。修订只影响下一次抽题，当前卷面保持原样。
          </p>
          <div className="question-list">
            {questions.slice(currentPage * 5, currentPage * 5 + 5).map((q) => (
              <div
                className={"library-row " + (!q.enabled ? "disabled-row" : "")}
                key={q.id}
              >
                <input
                  type="checkbox"
                  aria-label={"选择题目 " + q.id}
                  checked={checked.includes(q.id)}
                  onChange={(e) =>
                    setChecked(
                      e.target.checked
                        ? [...checked, q.id]
                        : checked.filter((id) => id !== q.id),
                    )
                  }
                />
                <div>
                  <small>
                    {q.subject} · {typeNames[q.type]} · {q.chapter}
                    {!q.enabled ? " · 已停用" : ""}
                  </small>
                  <p>{q.question}</p>
                </div>
                <button
                  onClick={() => {
                    setEditing(q);
                    setIsNew(false);
                  }}
                >
                  修订
                </button>
                <button
                  className="text-button danger"
                  disabled={busy}
                  onClick={() => {
                    if (
                      window.confirm("删除这道题？已保存的作答历史仍会保留。")
                    )
                      void save({
                        ...bank,
                        questions: bank.questions.filter(
                          (old) => old.id !== q.id,
                        ),
                      }).catch(() => {});
                  }}
                >
                  删
                </button>
              </div>
            ))}
          </div>
          {questions.length > 5 && (
            <div className="pager">
              <button
                disabled={!currentPage}
                onClick={() => setPage(currentPage - 1)}
              >
                上一页
              </button>
              <span>
                {currentPage + 1}/{Math.ceil(questions.length / 5)}
              </span>
              <button
                disabled={(currentPage + 1) * 5 >= questions.length}
                onClick={() => setPage(currentPage + 1)}
              >
                下一页
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

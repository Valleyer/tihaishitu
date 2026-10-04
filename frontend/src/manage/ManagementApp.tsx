import { useCallback, useEffect, useState, type ReactNode } from "react";
import { RichText } from "../components/RichText";
import {
  manageApi,
  ManageHttpError,
  type AuditLogView,
  type KnowledgeView,
  type ManageUser,
  type QuestionBatchImportResult,
  type QuestionOption,
  type QuestionRelation,
  type QuestionView,
} from "./api";
import { manageLabel, manageOptions } from "./manageLabels";
import "./manage.css";

type Page = "dashboard" | "questions" | "knowledge" | "reviews" | "imports" | "users" | "audit";

export default function ManagementApp() {
  const [user, setUser] = useState<ManageUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState<Page>("dashboard");
  const [error, setError] = useState("");
  const [accessError, setAccessError] = useState("");

  useEffect(() => {
    manageApi.me().then(setUser).catch((reason) => {
      if (reason instanceof ManageHttpError && reason.status === 401) {
        const next = window.location.pathname + window.location.search;
        window.location.assign("/login?next=" + encodeURIComponent(next));
        return;
      }
      setAccessError(reason instanceof Error ? reason.message : "当前账号没有管理后台权限。");
    }).finally(() => setLoading(false));
  }, []);
  if (loading) return <div className="manage-loading">正在核验管理会话…</div>;
  if (!user) return <div className="manage-loading"><div className="manage-access-denied"><h1>无法进入管理后台</h1><p>{accessError || "当前账号没有管理后台权限。"}</p><a href="/">返回学习主世界</a></div></div>;
  const admin = user.roles.includes("ADMIN");
  return (
    <div className="manage-shell">
      <aside className="manage-sidebar">
        <header><b>题海仕途</b><span>全服内容中台</span></header>
        <nav>
          <Nav active={page === "dashboard"} onClick={() => setPage("dashboard")}>仪表盘</Nav>
          <Nav active={page === "questions"} onClick={() => setPage("questions")}>题目管理</Nav>
          <Nav active={page === "knowledge"} onClick={() => setPage("knowledge")}>知识点管理</Nav>
          <Nav active={page === "reviews"} onClick={() => setPage("reviews")}>审核中心</Nav>
          {admin && <Nav active={page === "imports"} onClick={() => setPage("imports")}>批量导入</Nav>}
          {admin && <Nav active={page === "users"} onClick={() => setPage("users")}>用户与权限</Nav>}
          {admin && <Nav active={page === "audit"} onClick={() => setPage("audit")}>审计记录</Nav>}
        </nav>
        <footer>
          <span>{user.displayName}</span><small>{user.roles.map(role => manageLabel("role", role)).join(" · ") || "普通学习者"}</small>
          <button onClick={() => manageApi.logout().then(() => window.location.assign("/login"))}>退出登录</button>
          <a href="/">返回学习主世界</a>
        </footer>
      </aside>
      <main className="manage-main">
        {error && <div className="manage-error" onClick={() => setError("")}>{error}</div>}
        {page === "dashboard" && <Dashboard user={user} />}
        {page === "knowledge" && <KnowledgePage user={user} fail={setError} />}
        {page === "questions" && <QuestionPage user={user} fail={setError} />}
        {page === "reviews" && <QuestionPage user={user} fail={setError} reviewOnly />}
        {page === "imports" && admin && <ImportPage fail={setError} />}
        {page === "users" && admin && <UsersPage fail={setError} />}
        {page === "audit" && admin && <AuditPage fail={setError} />}
      </main>
    </div>
  );
}

function Dashboard({ user }: { user: ManageUser }) {
  const [knowledge, setKnowledge] = useState<number>();
  const [pending, setPending] = useState<number>();
  useEffect(() => {
    manageApi.knowledge({ size: 1 }).then((p) => setKnowledge(p.totalElements));
    manageApi.questions({ status: "pending_review", size: 1 }).then((p) => setPending(p.totalElements));
  }, []);
  return <section><PageTitle title="仪表盘" detail={`欢迎，${user.displayName}`} />
    <div className="metric-grid"><Metric label="全服知识点" value={knowledge} note="数学一正式知识坐标" />
      <Metric label="待审核题目" value={pending} note="贡献者提交的全服资源" />
      <Metric label="当前权限" value={user.roles.length} note={user.roles.map(role => manageLabel("role", role)).join(" / ")} /></div>
    <div className="manage-card"><h2>资源边界</h2><p>这里维护全服正式知识点与题目。玩家自己的藏书阁仍是私人学习空间，不会修改这里的官方资源。</p></div>
  </section>;
}

function ImportPage({ fail }: { fail: (value: string) => void }) {
  const [source, setSource] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<QuestionBatchImportResult | null>(null);
  let preview: { schemaVersion?: string; publish?: boolean; batch?: { subject?: string; sourceType?: string; sourceName?: string; examYear?: number }; questions?: unknown[] } | null = null;
  let parseError = "";
  if (source.trim()) {
    try { preview = JSON.parse(source); }
    catch { parseError = "JSON 格式尚不完整。"; }
  }
  const legacyFormat = preview?.schemaVersion === "global-question-bank/v1";
  const supportedFormat = preview?.schemaVersion === "global-question-batch/v2";
  const submit = () => {
    if (!preview || !supportedFormat) return;
    setBusy(true); setResult(null);
    manageApi.importQuestionBatch(preview)
      .then(setResult).catch((error) => fail(error.message)).finally(() => setBusy(false));
  };
  const loadFile = (file?: File) => {
    if (!file) return;
    file.text().then((text) => { setSource(text); setResult(null); })
      .catch(() => fail("无法读取所选文件。"));
  };
  return <section><PageTitle title="批量导入" detail="批量导入全服题目；题目通过 KnowledgePoint 自动成为学习资源" />
    <div className="import-workspace">
      <div className="manage-card import-source">
        <div className="import-heading"><div><h2>题目批次 JSON</h2><p>格式版本固定为 <code>global-question-batch/v2</code>，导入不会创建或修改 Book。</p></div>
          <label className="file-button">选择文件<input type="file" accept="application/json,.json" onChange={(event) => loadFile(event.target.files?.[0])} /></label></div>
        <textarea rows={24} spellCheck={false} value={source} onChange={(event) => { setSource(event.target.value); setResult(null); }} placeholder="粘贴题目批次 JSON，或选择文件…" />
      </div>
      <aside className="manage-card import-preview"><h2>导入预检</h2>
        {!source && <Empty>选择文件或粘贴 JSON 后，这里会显示题目批次摘要。</Empty>}
        {parseError && <p className="form-error">{parseError}</p>}
        {legacyFormat && <p className="form-error">这是旧版文集导入格式。新版系统的题目已经与 Book 解耦，请使用 global-question-batch/v2。</p>}
        {preview && !legacyFormat && !supportedFormat && <p className="form-error">格式必须为 global-question-batch/v2。</p>}
        {preview && <dl><dt>格式</dt><dd>{preview.schemaVersion || "未提供"}</dd><dt>科目</dt><dd>{preview.batch?.subject || "未提供"}</dd><dt>来源类型</dt><dd>{manageLabel("source", preview.batch?.sourceType)}</dd><dt>来源名称</dt><dd>{preview.batch?.sourceName || "未提供"}</dd><dt>年份</dt><dd>{preview.batch?.examYear ?? "—"}</dd><dt>题目数</dt><dd>{Array.isArray(preview.questions) ? preview.questions.length : 0}</dd><dt>导入状态</dt><dd>{preview.publish ? "直接发布" : "进入待审核"}</dd></dl>}
        <button className="primary" disabled={!supportedFormat || busy} onClick={submit}>{busy ? "导入中…" : "校验并导入"}</button>
        {result && <div className="import-result"><b>导入完成</b><p>{result.subject} · {result.sourceName}{result.examYear ? ` · ${result.examYear}` : ""}</p><p>题目 {result.questionCount} 道；新建 {result.createdQuestions}，更新 {result.updatedQuestions}</p><p>选项 {result.optionCount} 条；知识点关系 {result.relationCount} 条</p><small>导入编号：{result.importId}</small></div>}
      </aside>
    </div>
  </section>;
}

function KnowledgePage({ user, fail }: { user: ManageUser; fail: (value: string) => void }) {
  const [query, setQuery] = useState("");
  const [subject, setSubject] = useState("数学一");
  const [section, setSection] = useState("");
  const [chapter, setChapter] = useState("");
  const [status, setStatus] = useState("");
  const [items, setItems] = useState<KnowledgeView[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<KnowledgeView | null>(null);
  const editable = user.roles.some((role) => role === "REVIEWER" || role === "ADMIN");
  const load = useCallback(() => manageApi.knowledge({ query, subject, section, chapter, status, size: 50 })
    .then((page) => { setItems(page.content); setTotal(page.totalElements); })
    .catch((e) => fail(e.message)), [query, subject, section, chapter, status, fail]);
  useEffect(() => { void load(); }, [load]);
  return <section><PageTitle title="知识点管理" detail={`共 ${total} 条；code 是永久稳定身份`} />
    <div className="manage-toolbar knowledge-filters"><input placeholder="搜索 code / 名称 / alias" value={query} onChange={(e) => setQuery(e.target.value)} />
      <select value={subject} onChange={(e) => setSubject(e.target.value)}><option value="">全部科目</option><option>数学一</option></select>
      <select value={section} onChange={(e) => setSection(e.target.value)}><option value="">全部分科</option><option>高等数学</option><option>线性代数</option><option>概率论与数理统计</option></select>
      <input placeholder="章节精确筛选" value={chapter} onChange={(e) => setChapter(e.target.value)} />
      <select value={status} onChange={(e) => setStatus(e.target.value)}><option value="">全部状态</option><option value="active">有效</option><option value="deprecated">已停用/合并</option></select>
      <button onClick={load}>查询</button></div>
    <div className="split-workspace"><div className="data-table"><div className="table-head"><span>知识点编码</span><span>名称</span><span>章节</span><span>题目数</span><span>状态</span></div>
      {items.map((item) => <button className="table-row" key={item.id} onClick={() => setSelected(item)}><code>{item.code}</code><b>{item.name}</b><span>{item.chapter}</span><span>{item.questionCount}</span><i>{manageLabel("knowledgeStatus", item.status)}</i></button>)}</div>
      <aside className="detail-panel">{selected ? <KnowledgeEditor key={selected.id + selected.revision} point={selected} editable={editable} admin={user.roles.includes("ADMIN")} fail={fail} saved={(point) => { setSelected(point); load(); }} /> : <Empty>选择一条知识点查看详情</Empty>}</aside></div>
  </section>;
}

function KnowledgeEditor({ point: initial, editable, admin, fail, saved }: { point: KnowledgeView; editable: boolean; admin: boolean; fail: (v: string) => void; saved: (v: KnowledgeView) => void }) {
  const [point, setPoint] = useState(initial); const [busy, setBusy] = useState(false);
  const [targetQuery, setTargetQuery] = useState("");
  const [targets, setTargets] = useState<KnowledgeView[]>([]);
  const [target, setTarget] = useState<KnowledgeView | null>(null);
  const [mergeReason, setMergeReason] = useState("");
  const merge = () => {
    if (!target || !mergeReason.trim()) return;
    if (!window.confirm(`确认把 ${point.code} ${point.name} 合并到 ${target.code} ${target.name}？题目关系会随之迁移。`)) return;
    setBusy(true);
    manageApi.mergeKnowledge(point, target.id, mergeReason)
      .then((result) => saved(result.target)).catch((error) => fail(error.message)).finally(() => setBusy(false));
  };
  return <div className="editor"><header><code>{point.code}</code><span>修订版本 {point.revision}</span></header>
    <label>名称<input disabled={!editable} value={point.name} onChange={(e) => setPoint({ ...point, name: e.target.value })} /></label>
    <div className="form-row"><label>默认角色<select disabled={!editable} value={point.defaultRole} onChange={(e) => setPoint({ ...point, defaultRole: e.target.value as KnowledgeView["defaultRole"] })}>{manageOptions("knowledgeRole").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
      <label>状态<select disabled={!editable} value={point.status} onChange={(e) => setPoint({ ...point, status: e.target.value as KnowledgeView["status"] })}>{manageOptions("knowledgeStatus").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label></div>
    <p>{point.subject} / {point.section} / {point.chapter}</p>
    <label>别名（每行一个）<textarea disabled={!editable} value={point.aliases.join("\n")} onChange={(e) => setPoint({ ...point, aliases: e.target.value.split("\n").filter(Boolean) })} /></label>
    <label>说明<textarea disabled={!editable} value={point.description} onChange={(e) => setPoint({ ...point, description: e.target.value })} /></label>
    <label>知识点解析（Markdown + LaTeX）<textarea disabled={!editable} rows={8} value={point.explanation} onChange={(e) => setPoint({ ...point, explanation: e.target.value })} /></label>
    {editable && <button disabled={busy} onClick={() => { setBusy(true); manageApi.saveKnowledge(point).then(saved).catch((e) => fail(e.message)).finally(() => setBusy(false)); }}>保存知识点</button>}
    {admin && point.status === "active" && <fieldset className="editor-group merge-panel"><legend>合并知识点</legend>
      <p>旧知识点会保留为 deprecated；题目关系、旧 code、名称和别名将迁移到目标知识点。</p>
      <div className="inline-search"><input placeholder="搜索目标 code / 名称 / alias" value={targetQuery} onChange={(e) => setTargetQuery(e.target.value)} /><button onClick={() => manageApi.knowledge({ query: targetQuery, status: "active", size: 10 }).then((page) => setTargets(page.content.filter((item) => item.id !== point.id))).catch((error) => fail(error.message))}>搜索</button></div>
      {targets.length > 0 && <div className="merge-targets">{targets.map((item) => <button className={target?.id === item.id ? "selected" : ""} key={item.id} onClick={() => setTarget(item)}><code>{item.code}</code><span>{item.name}</span><small>{item.chapter}</small></button>)}</div>}
      {target && <p className="selected-target">目标：<code>{target.code}</code> {target.name}</p>}
      <label>合并原因<textarea rows={3} value={mergeReason} onChange={(event) => setMergeReason(event.target.value)} placeholder="记录口径重复、命名修订或知识体系调整原因" /></label>
      <button className="danger" disabled={busy || !target || !mergeReason.trim()} onClick={merge}>确认迁移并合并</button>
    </fieldset>}
  </div>;
}

export function QuestionPage({ user, fail, reviewOnly = false }: { user: ManageUser; fail: (v: string) => void; reviewOnly?: boolean }) {
  const [query, setQuery] = useState(""); const [status, setStatus] = useState(reviewOnly ? "pending_review" : "");
  const [items, setItems] = useState<QuestionView[]>([]); const [selected, setSelected] = useState<QuestionView | null>(null);
  const [creating, setCreating] = useState(false);
  const [pagination, setPagination] = useState({ page: 0, reviewOnly });
  const page = pagination.reviewOnly === reviewOnly ? pagination.page : 0;
  const [totalPages, setTotalPages] = useState(0); const [totalElements, setTotalElements] = useState(0);
  const load = useCallback(() => manageApi.questions({ query, status: reviewOnly ? "pending_review" : status, page, size: 20 })
    .then((result) => {
      setTotalPages(result.totalPages); setTotalElements(result.totalElements);
      if (page > 0 && result.content.length === 0 && page >= result.totalPages) {
        setItems([]); setPagination({ page: page - 1, reviewOnly }); return;
      }
      setItems(result.content);
    }).catch((e) => fail(e.message)), [query, status, reviewOnly, page, fail]);
  useEffect(() => { void load(); }, [load]);
  return <section><PageTitle title={reviewOnly ? "审核中心" : "题目管理"} detail="原始题型、游戏展示与判题方式分开维护" />
    <div className="manage-toolbar"><input placeholder="搜索题干 / 来源 / 题号" value={query} onChange={(e) => { setQuery(e.target.value); setPagination({ page: 0, reviewOnly }); }} />
      {!reviewOnly && <select value={status} onChange={(e) => { setStatus(e.target.value); setPagination({ page: 0, reviewOnly }); }}><option value="">全部状态</option>{manageOptions("questionStatus").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select>}
      <button onClick={load}>查询</button>{!reviewOnly && <button className="primary" onClick={() => { setCreating(true); setSelected(null); }}>新建题目</button>}</div>
    <div className="split-workspace"><div className="question-list-panel"><div className="data-table manage-question-list"><div className="table-head question-cols"><span>来源</span><span>原题型</span><span>判题</span><span>状态</span></div>
      {items.map((item) => <button className="table-row question-cols" key={item.id} onClick={() => { manageApi.question(item.id).then(setSelected).catch((e) => fail(e.message)); setCreating(false); }}><b>{item.examYear ? `${item.examYear} · ${item.questionNumber}` : item.sourceName || "自建题"}</b><span>{manageLabel("questionType", item.questionType)}</span><span>{manageLabel("grading", item.gradingMode)}</span><i>{manageLabel("questionStatus", item.status)}</i></button>)}</div>
      <div className="manage-pagination"><span>{reviewOnly ? `待审核共 ${totalElements} 道` : `共 ${totalElements} 道`}</span><span>第 {totalPages === 0 ? 0 : page + 1} / {totalPages} 页</span><div><button disabled={page === 0} onClick={() => setPagination({ page: page - 1, reviewOnly })}>上一页</button><button disabled={totalPages === 0 || page >= totalPages - 1} onClick={() => setPagination({ page: page + 1, reviewOnly })}>下一页</button></div></div></div>
      <aside className="detail-panel wide">{(selected || creating) ? <QuestionEditor key={selected?.id || "new"} initial={selected} user={user} fail={fail} saved={(q) => { setSelected(q); setCreating(false); load(); }} /> : <Empty>选择题目查看，或新建草稿</Empty>}</aside></div>
  </section>;
}

function QuestionEditor({ initial, user, fail, saved }: { initial: QuestionView | null; user: ManageUser; fail: (v: string) => void; saved: (v: QuestionView) => void }) {
  const [question, setQuestion] = useState<Partial<QuestionView>>(initial || { subject: "数学一", sourceType: "custom", questionType: "single_choice", presentationType: "single_choice", gradingMode: "auto", difficulty: 2, standardAnswer: "A", options: [], knowledgePoints: [] });
  const [answerText, setAnswerText] = useState(JSON.stringify(question.standardAnswer ?? "", null, 2));
  const [knowledgeQuery, setKnowledgeQuery] = useState(""); const [matches, setMatches] = useState<KnowledgeView[]>([]); const [busy, setBusy] = useState(false);
  const [rejecting, setRejecting] = useState(false); const [rejectComment, setRejectComment] = useState("");
  const canReview = user.id !== initial?.createdBy && user.roles.some((r) => r === "REVIEWER" || r === "ADMIN");
  const addOption = () => setQuestion({ ...question, options: [...(question.options || []), { key: String.fromCharCode(65 + (question.options?.length || 0)), text: "", correct: false, sortOrder: question.options?.length || 0 }] });
  const moveKnowledge = (index: number, offset: number) => {
    const relations = [...(question.knowledgePoints || [])];
    const destination = index + offset;
    if (destination < 0 || destination >= relations.length) return;
    [relations[index], relations[destination]] = [relations[destination], relations[index]];
    setQuestion({ ...question, knowledgePoints: relations.map((relation, sortOrder) => ({ ...relation, sortOrder })) });
  };
  const save = async () => { try { setBusy(true); const payload = { ...question, standardAnswer: JSON.parse(answerText) }; const result = initial ? await manageApi.saveQuestion(payload as QuestionView) : await manageApi.createQuestion(payload); saved(result); } catch (e) { fail(e instanceof Error ? e.message : "保存失败"); } finally { setBusy(false); } };
  return <div className="editor question-editor"><header><b>{initial ? `题目 ${initial.id.slice(0, 8)}` : "新建全服题目草稿"}</b><span>{manageLabel("questionStatus", question.status || "draft")} {question.revision ? `· 修订版本 ${question.revision}` : ""}</span></header>
    <div className="form-grid"><label>科目<input value={question.subject || ""} onChange={(e) => setQuestion({ ...question, subject: e.target.value })} /></label><label>来源类型<select value={question.sourceType} onChange={(e) => setQuestion({ ...question, sourceType: e.target.value })}>{manageOptions("source").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
      <label>来源名称<input value={question.sourceName || ""} onChange={(e) => setQuestion({ ...question, sourceName: e.target.value })} /></label><label>年份<input type="number" value={question.examYear || ""} onChange={(e) => setQuestion({ ...question, examYear: Number(e.target.value) || undefined })} /></label><label>题号<input value={question.questionNumber || ""} onChange={(e) => setQuestion({ ...question, questionNumber: e.target.value })} /></label><label>难度<select value={question.difficulty} onChange={(e) => setQuestion({ ...question, difficulty: Number(e.target.value) })}>{[1,2,3,4,5].map((n) => <option key={n}>{n}</option>)}</select></label>
      <label>原始题型<select value={question.questionType} onChange={(e) => setQuestion({ ...question, questionType: e.target.value })}>{manageOptions("questionType").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label><label>游戏展示<select value={question.presentationType} onChange={(e) => setQuestion({ ...question, presentationType: e.target.value })}>{manageOptions("presentation").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label><label>判题模式<select value={question.gradingMode} onChange={(e) => setQuestion({ ...question, gradingMode: e.target.value })}>{manageOptions("grading").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label></div>
    <label>题干（Markdown + LaTeX）<textarea rows={8} value={question.content || ""} onChange={(e) => setQuestion({ ...question, content: e.target.value })} /></label><details><summary>预览题干</summary><div className="markdown-preview"><RichText>{question.content || "暂无题干"}</RichText></div></details>
    <label>标准答案（JSON）<textarea rows={3} value={answerText} onChange={(e) => setAnswerText(e.target.value)} /></label><label>完整解析<textarea rows={7} value={question.analysis || ""} onChange={(e) => setQuestion({ ...question, analysis: e.target.value })} /></label>
    <fieldset className="editor-group"><legend>游戏化选项</legend>{(question.options || []).map((option, index) => <OptionRow key={index} option={option} changed={(next) => setQuestion({ ...question, options: question.options!.map((old, i) => i === index ? next : old) })} remove={() => setQuestion({ ...question, options: question.options!.filter((_, i) => i !== index) })} />)}<button onClick={addOption}>添加选项</button></fieldset>
    <fieldset className="editor-group"><legend>知识点绑定</legend><div className="inline-search"><input placeholder="搜索 code / 名称 / alias" value={knowledgeQuery} onChange={(e) => setKnowledgeQuery(e.target.value)} /><button onClick={() => manageApi.knowledge({ query: knowledgeQuery, status: "active", size: 10 }).then((p) => setMatches(p.content)).catch((e) => fail(e.message))}>搜索</button></div>
      {matches.length > 0 && <div className="search-results">{matches.map((point) => <button key={point.id} onClick={() => { if (!(question.knowledgePoints || []).some((r) => (r.id || r.knowledgePointId) === point.id)) setQuestion({ ...question, knowledgePoints: [...(question.knowledgePoints || []), { id: point.id, knowledgePointId: point.id, code: point.code, name: point.name, role: "core", sortOrder: question.knowledgePoints?.length || 0 }] }); }}>{point.code} {point.name}</button>)}</div>}
      <div className="relations">{(question.knowledgePoints || []).map((relation, index) => <div key={relation.id || relation.knowledgePointId}><span><code>{relation.code}</code> {relation.name}</span><select value={relation.role} onChange={(e) => setQuestion({ ...question, knowledgePoints: question.knowledgePoints!.map((r, i) => i === index ? { ...r, role: e.target.value as QuestionRelation["role"] } : r) })}><option value="core">核心</option><option value="auxiliary">辅助</option></select><span className="relation-order"><button aria-label="上移知识点" disabled={index === 0} onClick={() => moveKnowledge(index, -1)}>↑</button><button aria-label="下移知识点" disabled={index === (question.knowledgePoints?.length || 0) - 1} onClick={() => moveKnowledge(index, 1)}>↓</button></span><button onClick={() => setQuestion({ ...question, knowledgePoints: question.knowledgePoints!.filter((_, i) => i !== index) })}>解绑</button></div>)}</div>
    </fieldset>
    {question.reviewComment && <p className="review-comment">审核意见：{question.reviewComment}</p>}
    {rejecting && <div className="review-decision"><label>退回修改原因<textarea autoFocus rows={4} value={rejectComment} onChange={event => setRejectComment(event.target.value)} placeholder="请明确写出需要修改的内容" /></label><div><button onClick={() => setRejecting(false)}>取消</button><button className="reject" disabled={!rejectComment.trim()} onClick={() => initial && manageApi.reviewQuestion(initial, false, rejectComment.trim()).then(saved).catch((e) => fail(e.message))}>确认退回修改</button></div></div>}
    <div className="editor-actions"><button className="primary" disabled={busy} onClick={save}>保存草稿</button>{initial && ["draft","rejected"].includes(initial.status) && <button onClick={() => manageApi.submitQuestion(initial).then(saved).catch((e) => fail(e.message))}>提交审核</button>}{initial?.status === "pending_review" && canReview && <><button className="approve" onClick={() => manageApi.reviewQuestion(initial, true, "审核通过并发布").then(saved).catch((e) => fail(e.message))}>审核通过并发布</button><button className="reject" onClick={() => setRejecting(true)}>退回修改</button></>}</div>
  </div>;
}

function OptionRow({ option, changed, remove }: { option: QuestionOption; changed: (v: QuestionOption) => void; remove: () => void }) { return <div className="option-edit"><input className="option-key" value={option.key} onChange={(e) => changed({ ...option, key: e.target.value })} /><input value={option.text} onChange={(e) => changed({ ...option, text: e.target.value })} /><label><input type="checkbox" checked={option.correct} onChange={(e) => changed({ ...option, correct: e.target.checked })} />正确</label><button onClick={remove}>删除</button></div>; }

function AuditPage({ fail }: { fail: (value: string) => void }) {
  const [action, setAction] = useState("");
  const [entityType, setEntityType] = useState("");
  const [actor, setActor] = useState("");
  const [items, setItems] = useState<AuditLogView[]>([]);
  const [total, setTotal] = useState(0);
  const load = useCallback(() => manageApi.auditLogs({ action, entityType, actor, size: 100 })
    .then((page) => { setItems(page.content); setTotal(page.totalElements); })
    .catch((error) => fail(error.message)), [action, entityType, actor, fail]);
  useEffect(() => { void load(); }, [load]);
  return <section><PageTitle title="审计记录" detail={`共 ${total} 条；只读展示全服内容与权限变更`} />
    <div className="manage-toolbar audit-filters"><input placeholder="操作者账号 / 名称 / UUID" value={actor} onChange={(event) => setActor(event.target.value)} />
      <select value={action} onChange={(event) => setAction(event.target.value)}><option value="">全部动作</option><option value="KNOWLEDGE_UPDATED">知识点修改</option><option value="KNOWLEDGE_ALIASES_UPDATED">别名修改</option><option value="KNOWLEDGE_MERGED">知识点合并</option><option value="QUESTION_CREATED">题目创建</option><option value="QUESTION_UPDATED">题目修改</option><option value="QUESTION_SUBMITTED">提交审核</option><option value="QUESTION_REVIEW_APPROVED">审核通过</option><option value="QUESTION_REVIEW_REJECTED">审核退回</option><option value="QUESTION_ARCHIVED">题目归档</option><option value="USER_ROLE_UPDATED">用户权限修改</option></select>
      <select value={entityType} onChange={(event) => setEntityType(event.target.value)}><option value="">全部实体</option><option value="knowledge_point">知识点</option><option value="question">题目</option><option value="learner_account">用户</option><option value="question_bank">文集</option></select>
      <button onClick={load}>查询</button></div>
    <div className="audit-list">{items.map((item) => <article key={item.id} className="audit-row"><header><b>{auditAction(item.action)}</b><time>{new Date(item.createdAt).toLocaleString("zh-CN", { hour12: false })}</time></header>
      <p><span>{item.actorDisplayName || "系统"}</span>{item.actorUsername && <code>{item.actorUsername}</code>} · {manageLabel("entity", item.entityType)} · <code>{item.entityId}</code></p>
      <details><summary>查看技术详情</summary><pre>{JSON.stringify(item.metadata, null, 2)}</pre></details></article>)}{items.length === 0 && <Empty>暂无符合条件的审计记录</Empty>}</div>
  </section>;
}

function auditAction(action: string) {
  const labels: Record<string, string> = {
    KNOWLEDGE_UPDATED: "知识点修改", KNOWLEDGE_ALIASES_UPDATED: "知识点别名修改", KNOWLEDGE_MERGED: "知识点合并",
    QUESTION_CREATED: "题目创建", QUESTION_UPDATED: "题目修改", QUESTION_SUBMITTED: "题目提交审核",
    QUESTION_REVIEW_APPROVED: "题目审核通过", QUESTION_REVIEW_REJECTED: "题目审核退回", QUESTION_ARCHIVED: "题目归档",
    USER_CREATED: "用户创建", USER_ROLE_UPDATED: "用户权限修改", QUESTION_BANK_IMPORTED: "题库导入",
  };
  return labels[action] || action;
}

function UsersPage({ fail }: { fail: (v: string) => void }) {
  const [users, setUsers] = useState<ManageUser[]>([]); const [draft, setDraft] = useState({ username: "", displayName: "", password: "", roles: [] as string[] });
  const load = useCallback(() => manageApi.users().then(setUsers).catch((e) => fail(e.message)), [fail]); useEffect(() => { void load(); }, [load]);
  return <section><PageTitle title="用户与权限" detail="所有学习者使用同一个账号；后台角色可以为空" /><div className="manage-card create-user"><h2>创建学习账号</h2><input placeholder="用户名" value={draft.username} onChange={(e) => setDraft({ ...draft, username: e.target.value })} /><input placeholder="显示名" value={draft.displayName} onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} /><input type="password" placeholder="初始密码（至少10位）" value={draft.password} onChange={(e) => setDraft({ ...draft, password: e.target.value })} /><button onClick={() => manageApi.createUser(draft).then(() => { setDraft({ username: "", displayName: "", password: "", roles: [] }); load(); }).catch((e) => fail(e.message))}>创建用户</button></div>
    <div className="user-list">{users.map((user) => <UserRow key={user.id} user={user} saved={load} fail={fail} />)}</div></section>;
}

function UserRow({ user: initial, saved, fail }: { user: ManageUser; saved: () => void; fail: (v: string) => void }) {
  const [user, setUser] = useState(initial); return <div className="user-row"><div><b>{user.displayName}</b><code>{user.username}</code></div><select value={user.status} onChange={(e) => setUser({ ...user, status: e.target.value as ManageUser["status"] })}>{manageOptions("userStatus").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select><div className="role-checks">{["CONTRIBUTOR","REVIEWER","ADMIN"].map((role) => <label key={role}><input type="checkbox" checked={user.roles.includes(role)} onChange={(e) => setUser({ ...user, roles: e.target.checked ? [...user.roles, role] : user.roles.filter((r) => r !== role) })} />{manageLabel("role", role)}</label>)}</div><button onClick={() => manageApi.saveUser(user).then(saved).catch((e) => fail(e.message))}>保存</button></div>;
}

function Nav({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) { return <button className={active ? "active" : ""} onClick={onClick}>{children}</button>; }
function PageTitle({ title, detail }: { title: string; detail: string }) { return <header className="page-title"><div><h1>{title}</h1><p>{detail}</p></div></header>; }
function Metric({ label, value, note }: { label: string; value?: number; note: string }) { return <div className="metric"><span>{label}</span><b>{value ?? "—"}</b><small>{note}</small></div>; }
function Empty({ children }: { children: ReactNode }) { return <div className="empty-state">{children}</div>; }

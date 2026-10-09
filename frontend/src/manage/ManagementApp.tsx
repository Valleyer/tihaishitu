import { useCallback, useEffect, useRef, useState, type ChangeEvent, type ReactNode } from "react";
import { RichText } from "../components/RichText";
import { QuestionStemImage } from "../components/QuestionStemImage";
import { PAGE_SIZE } from "../pagination";
import {
  manageApi,
  ManageHttpError,
  type AuditLogView,
  type KnowledgeView,
  type KnowledgeBatchImportResult,
  type ManageUser,
  type ManagedBook,
  type ManagedBookDetail,
  type ManagedChapter,
  type QuestionBatchImportResult,
  type QuestionOption,
  type QuestionRelation,
  type QuestionView,
  type QuestionSourceView,
  type QuestionReportView,
} from "./api";
import { manageLabel, manageOptions, questionTypeContract } from "./manageLabels";
import "./manage.css";

type Page = "dashboard" | "questions" | "reports" | "sources" | "knowledge" | "books" | "reviews" | "imports" | "users" | "audit";
const downloadJson=(name:string,value:unknown)=>{const url=URL.createObjectURL(new Blob([JSON.stringify(value,null,2)],{type:"application/json"}));const link=document.createElement("a");link.href=url;link.download=name;link.click();URL.revokeObjectURL(url)};

export default function ManagementApp() {
  const initialQuestionId = new URLSearchParams(window.location.search).get("questionId") || undefined;
  const [user, setUser] = useState<ManageUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState<Page>(initialQuestionId ? "questions" : "dashboard");
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
  if (!user) return <div className="manage-loading"><div className="manage-access-denied"><h1>无法进入管理后台</h1><p>{accessError || "当前账号没有管理后台权限。"}</p><a href="/">返回万境中枢</a></div></div>;
  const admin = user.roles.includes("ADMIN");
  const reviewer = user.roles.some(role=>role==="REVIEWER"||role==="ADMIN");
  return (
    <div className="manage-shell">
      <aside className="manage-sidebar">
        <header><img src="/brand-logo.png" alt="" /><div><b>万境书院</b><span>全服内容中台</span></div></header>
        <nav>
          <Nav active={page === "dashboard"} onClick={() => setPage("dashboard")}>管理首页</Nav>
          <Nav active={page === "questions"} onClick={() => setPage("questions")}>题目管理</Nav>
          {reviewer&&<Nav active={page === "reports"} onClick={() => setPage("reports")}>题目反馈</Nav>}
          {admin && <Nav active={page === "sources"} onClick={() => setPage("sources")}>来源管理</Nav>}
          <Nav active={page === "knowledge"} onClick={() => setPage("knowledge")}>知识管理</Nav>
          {admin && <Nav active={page === "books"} onClick={() => setPage("books")}>文集管理</Nav>}
          <Nav active={page === "reviews"} onClick={() => setPage("reviews")}>审核中心</Nav>
          {admin && <Nav active={page === "imports"} onClick={() => setPage("imports")}>批量导入</Nav>}
          {admin && <Nav active={page === "users"} onClick={() => setPage("users")}>用户权限</Nav>}
          {admin && <Nav active={page === "audit"} onClick={() => setPage("audit")}>审计记录</Nav>}
        </nav>
        <footer>
          <span>{user.displayName}</span><small>{user.roles.map(role => manageLabel("role", role)).join(" · ") || "普通学习者"}</small>
          <button onClick={() => manageApi.logout().then(() => window.location.assign("/login"))}>退出登录</button>
          <a href="/">返回万境中枢</a>
        </footer>
      </aside>
      <main className="manage-main">
        {error && <div className="manage-error" onClick={() => setError("")}>{error}</div>}
        {page === "dashboard" && <Dashboard user={user} />}
        {page === "knowledge" && <KnowledgePage user={user} fail={setError} />}
        {page === "books" && admin && <BooksManagementPage fail={setError} />}
        {page === "questions" && <QuestionPage user={user} fail={setError} initialQuestionId={initialQuestionId} />}
        {page === "reports" && reviewer && <QuestionReportsPage fail={setError} />}
        {page === "sources" && admin && <SourcePage fail={setError} />}
        {page === "reviews" && <QuestionPage user={user} fail={setError} reviewOnly />}
        {page === "imports" && admin && <ImportPage fail={setError} />}
        {page === "users" && admin && <UsersPage currentUser={user} fail={setError} />}
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
  return <section><PageTitle title="管理首页" detail={`欢迎，${user.displayName}`} />
    <div className="metric-grid"><Metric label="全服知识点" value={knowledge} note="全局知识体系中的知识点总量" />
      <Metric label="待审核题目" value={pending} note="贡献者提交的全服资源" />
      <Metric label="当前权限" value={user.roles.length} note={user.roles.map(role => manageLabel("role", role)).join(" / ")} /></div>
    <div className="manage-card"><h2>资源边界</h2><p>这里维护全服正式知识点与题目。玩家自己的藏书阁仍是私人学习空间，不会修改这里的官方资源。</p></div>
  </section>;
}

export function QuestionReportsPage({fail}:{fail:(value:string)=>void}) {
  const [items,setItems]=useState<QuestionReportView[]>([]); const [status,setStatus]=useState("open"); const [reason,setReason]=useState("");
  const [page,setPage]=useState(0); const [total,setTotal]=useState(0); const [totalPages,setTotalPages]=useState(0);
  const load=useCallback(()=>manageApi.questionReports({status,reason,page,size:PAGE_SIZE}).then(result=>{setItems(result.content);setTotal(result.totalElements);setTotalPages(result.totalPages)}).catch(error=>fail((error as Error).message)),[status,reason,page,fail]);
  useEffect(()=>{void load()},[load]);
  const update=(id:string,next:"resolved"|"dismissed")=>manageApi.updateQuestionReport(id,next).then(load).catch(error=>fail((error as Error).message));
  const reasonName=(value:string)=>({content_error:"题干有误",answer_error:"答案有误",analysis_error:"解析有误",format_error:"排版有误",other:"其他"}[value]||value);
  return <section><PageTitle title="题目反馈" detail={`共 ${total} 条`} /><div className="manage-toolbar"><select value={status} onChange={event=>{setStatus(event.target.value);setPage(0)}}><option value="">全部状态</option><option value="open">待处理</option><option value="resolved">已处理</option><option value="dismissed">已忽略</option></select><select value={reason} onChange={event=>{setReason(event.target.value);setPage(0)}}><option value="">全部类型</option><option value="content_error">题干有误</option><option value="answer_error">答案有误</option><option value="analysis_error">解析有误</option><option value="format_error">排版有误</option><option value="other">其他</option></select></div>
    <div className="data-table"><div className="table-head report-cols"><span>题目</span><span>类型 / 备注</span><span>学习者</span><span>提交时间</span><span>处理</span></div>{items.map(item=><div className="table-row report-cols" key={item.id}><span><b>{item.sourceName}</b><small>{item.examYear?`${item.examYear}年 `:""}{item.questionNumber?`第${item.questionNumber}题`:""}</small><a href={`/manage?questionId=${encodeURIComponent(item.questionId)}`}>编辑题目</a></span><span><b>{reasonName(item.reason)}</b><small>{item.comment||"未填写补充说明"}</small></span><span>{item.learnerName}</span><time>{new Date(item.createdAt).toLocaleString("zh-CN")}</time><span>{item.status==="open"?<><button onClick={()=>void update(item.id,"resolved")}>标记已处理</button><button onClick={()=>void update(item.id,"dismissed")}>忽略</button></>:item.status==="resolved"?"已处理":"已忽略"}</span></div>)}</div>
    <div className="manage-pagination"><span>第 {totalPages?page+1:0} / {totalPages} 页</span><div><button disabled={page===0} onClick={()=>setPage(page-1)}>上一页</button><button disabled={!totalPages||page>=totalPages-1} onClick={()=>setPage(page+1)}>下一页</button></div></div>
  </section>;
}

export function ImportPage({ fail }: { fail: (value: string) => void }) {
  type ImportKind="questions"|"knowledge"|"guides"|"remedial";
  const [kind,setKind]=useState<ImportKind>("questions"); const [source,setSource]=useState(""); const [busy,setBusy]=useState(false); const [questionResult,setQuestionResult]=useState<QuestionBatchImportResult|null>(null); const [knowledgeResult,setKnowledgeResult]=useState<KnowledgeBatchImportResult|null>(null); const [extraResult,setExtraResult]=useState("");
  let preview: Record<string,unknown>|null=null; let parseError=""; if(source.trim()){try{preview=JSON.parse(source)}catch{parseError="JSON 格式尚不完整。"}}
  const expected={questions:"global-question-batch/v4",knowledge:"global-knowledge-batch/v2",guides:"knowledge-guide-batch/v1",remedial:"remedial-question-batch/v1"}[kind];
  // 题目批次的 canonical 是 v4；v3 只作为历史兼容输入，必须真的能提交，而不是只在文案里兼容。
  const legacyQuestions=kind==="questions"&&preview?.schemaVersion==="global-question-batch/v3";
  const supported=preview?.schemaVersion===expected||legacyQuestions;
  const formatHint=kind==="questions"?`${expected}（正式格式）；global-question-batch/v3 仅历史兼容，推荐改用 v4`:expected;
  const resetResult=()=>{setQuestionResult(null);setKnowledgeResult(null);setExtraResult("")};
  const loadFile=(file?:File)=>{if(file)file.text().then(text=>{setSource(text);resetResult()}).catch(()=>fail("无法读取所选文件。"))};
  const submit=()=>{if(!preview||!supported)return;setBusy(true);let action:Promise<unknown>;if(kind==="questions")action=manageApi.importQuestionBatch(preview).then(setQuestionResult);else if(kind==="knowledge")action=manageApi.importKnowledgeBatch(preview).then(setKnowledgeResult);else if(kind==="guides")action=manageApi.importKnowledgeGuides(preview).then(result=>setExtraResult(`讲解新建 ${result.created}，更新 ${result.updated}`));else action=manageApi.importRemedialQuestions(preview).then(result=>setExtraResult(`父题 ${result.parentCount} 道；子题新建 ${result.created}，更新 ${result.updated}，归档 ${result.archived}`));action.catch(error=>fail((error as Error).message)).finally(()=>setBusy(false))};
  const select=(next:ImportKind)=>{setKind(next);setSource("");resetResult()};
  const batch=preview?.batch as Record<string,unknown>|undefined; const book=preview?.book as Record<string,unknown>|undefined; const questions=preview?.questions as unknown[]|undefined; const chapters=preview?.chapters as unknown[]|undefined; const points=preview?.knowledgePoints as unknown[]|undefined;
  const title={questions:"题目",knowledge:"知识点",guides:"知识讲解",remedial:"补救子题"}[kind];
  return <section><PageTitle title="批量导入" detail="资源按稳定 JSON Schema 导入"/><div className="import-tabs"><button className={kind==="questions"?"active":""} onClick={()=>select("questions")}>题目</button><button className={kind==="knowledge"?"active":""} onClick={()=>select("knowledge")}>知识点</button><button className={kind==="guides"?"active":""} onClick={()=>select("guides")}>知识讲解</button><button className={kind==="remedial"?"active":""} onClick={()=>select("remedial")}>补救子题</button></div>
    <div className="import-workspace"><div className="manage-card import-source"><div className="import-heading"><div><h2>{title}批次 JSON</h2><p>当前格式为 <code>{formatHint}</code>。</p></div><div><label className="file-button">选择文件<input type="file" accept="application/json,.json" onChange={event=>loadFile(event.target.files?.[0])}/></label></div></div><textarea rows={24} spellCheck={false} value={source} onChange={event=>{setSource(event.target.value);resetResult()}} placeholder="粘贴 JSON，或选择文件…"/></div>
      <aside className="manage-card import-preview"><h2>导入预检</h2>{!source&&<Empty>选择文件或粘贴 JSON 后显示摘要。</Empty>}{parseError&&<p className="form-error">{parseError}</p>}{preview&&!supported&&<p className="form-error">格式必须为 {formatHint}。</p>}{preview&&supported&&legacyQuestions&&<p className="form-note">已识别 v3 历史兼容格式；本次导入会按 v3 兼容规则处理，后续请改用 v4。</p>}{preview&&<dl><dt>格式</dt><dd>{String(preview.schemaVersion||"未提供")}</dd>{kind==="questions"?<><dt>来源</dt><dd>{String(batch?.sourceName||"未提供")}</dd><dt>题目数</dt><dd>{questions?.length||0}</dd></>:kind==="knowledge"?<><dt>文集</dt><dd>{String(book?.name||"未提供")}</dd><dt>章节数</dt><dd>{chapters?.length||0}</dd><dt>知识点数</dt><dd>{points?.length||0}</dd></>:<><dt>记录数</dt><dd>{Array.isArray(preview[kind==="guides"?"guides":"parents"])?(preview[kind==="guides"?"guides":"parents"] as unknown[]).length:0}</dd></>}</dl>}<button className="primary" disabled={!supported||busy} onClick={submit}>{busy?"导入中…":"确认导入"}</button>
      {questionResult&&<div className="import-result"><b>题目导入完成</b><p>{questionResult.sourceName} · {questionResult.schemaVersion}</p><p>题目 {questionResult.questionCount} 道；新建 {questionResult.createdQuestions}，更新 {questionResult.updatedQuestions}</p></div>}{knowledgeResult&&<div className="import-result"><b>知识点导入完成</b><p>{knowledgeResult.bookName}</p><p>章节 {knowledgeResult.chapterCount}；知识点 {knowledgeResult.knowledgePointCount}；新建 {knowledgeResult.createdKnowledgePoints}，更新 {knowledgeResult.updatedKnowledgePoints}</p><p>绑定关系 {knowledgeResult.membershipCount}；别名 {knowledgeResult.aliasCount}</p></div>}{extraResult&&<div className="import-result"><b>{title}导入完成</b><p>{extraResult}</p></div>}</aside>
    </div></section>;
}

export function KnowledgePage({ user, fail }: { user: ManageUser; fail: (value: string) => void }) {
  const [query,setQuery]=useState(""); const [bookId,setBookId]=useState(""); const [chapterId,setChapterId]=useState(""); const [membership,setMembership]=useState("all");
  const [items,setItems]=useState<KnowledgeView[]>([]); const [books,setBooks]=useState<ManagedBook[]>([]); const [bookDetail,setBookDetail]=useState<ManagedBookDetail>();
  const [page,setPage]=useState(0); const [total,setTotal]=useState(0); const [totalPages,setTotalPages]=useState(0); const [checked,setChecked]=useState<string[]>([]); const [selected,setSelected]=useState<KnowledgeView|null>(null);
  const editable = user.roles.some((role) => role === "REVIEWER" || role === "ADMIN");
  const admin=user.roles.includes("ADMIN");
  const load=useCallback(()=>manageApi.knowledge({query,bookId:membership==="unassigned"?"":bookId,chapterId:membership==="unassigned"?"":chapterId,membership,page,size:PAGE_SIZE}).then(result=>{setItems(result.content);setTotal(result.totalElements);setTotalPages(result.totalPages);setChecked([]);if(page>0&&!result.content.length)setPage(page-1)}).catch(e=>fail(e.message)),[query,bookId,chapterId,membership,page,fail]);
  useEffect(()=>{manageApi.books().then(setBooks).catch(e=>fail(e.message))},[fail]);
  useEffect(()=>{if(bookId)manageApi.book(bookId).then(setBookDetail).catch(e=>fail(e.message));else setBookDetail(undefined)},[bookId,fail]);
  useEffect(() => { void load(); }, [load]);
  const toggle=(id:string)=>setChecked(values=>values.includes(id)?values.filter(value=>value!==id):[...values,id]);
  const remove=async()=>{if(!checked.length||!window.confirm(`确认尝试永久删除所选 ${checked.length} 个知识点？\n\n只有无题目、无文集归属、无合并历史且无学习历史的孤儿知识点会被删除。`))return;try{const result=await manageApi.bulkDeleteKnowledge(checked);setSelected(null);await load();const blocked=result.blocked.map(item=>`“${item.name}”：${item.reason}`).join("\n");window.alert(`已删除 ${result.deleted} 个知识点。${blocked?`\n\n未删除：\n${blocked}`:""}`)}catch(error){fail((error as Error).message)}};
  const exportGuides=async(selectedOnly:boolean)=>{try{const value=await manageApi.exportKnowledgeGuides({knowledgePointIds:selectedOnly?checked:[],bookId:selectedOnly?undefined:bookId||undefined,chapterId:selectedOnly?undefined:chapterId||undefined});downloadJson("knowledge-guide-generation.json",value)}catch(error){fail((error as Error).message)}};
  const chapters=bookDetail?.chapters||[];
  const pager=<div className="manage-pagination"><span>共 {total} 条 · 已选 {checked.length} 条</span>{admin&&<button className="danger" disabled={!checked.length} onClick={remove}>批量删除（{checked.length}）</button>}<span>第 {totalPages?page+1:0} / {totalPages} 页</span><div><button disabled={page===0} onClick={()=>setPage(page-1)}>上一页</button><button disabled={!totalPages||page>=totalPages-1} onClick={()=>setPage(page+1)}>下一页</button></div></div>;
  return <section className="knowledge-management-page"><PageTitle title="知识管理" detail={`共 ${total} 条`} />
    <div className="manage-toolbar knowledge-filters"><input placeholder="搜索名称 / 编码 / 别名" value={query} onChange={e=>{setQuery(e.target.value);setPage(0)}} />
      <select aria-label="文集" value={bookId} disabled={membership==="unassigned"} onChange={e=>{setBookId(e.target.value);setChapterId("");setPage(0)}}><option value="">全部文集</option>{books.map(item=><option value={item.id} key={item.id}>{item.name}</option>)}</select>
      <select aria-label="章节" value={chapterId} disabled={membership==="unassigned"||!bookId} onChange={e=>{setChapterId(e.target.value);setPage(0)}}><option value="">全部章节</option>{chapters.map(item=><option value={item.id} key={item.id}>{item.name}</option>)}</select>
      <select aria-label="归属" value={membership} onChange={e=>{const next=e.target.value;setMembership(next);if(next==="unassigned"){setBookId("");setChapterId("")}setPage(0)}}><option value="all">全部</option><option value="assigned">已加入文集</option><option value="unassigned">未加入文集</option></select>
      <button onClick={load}>查询</button><button disabled={!checked.length} onClick={()=>exportGuides(true)}>导出勾选项供 AI 生成讲解</button><button onClick={()=>exportGuides(false)}>导出当前范围</button></div>
    <div className="split-workspace knowledge-management-workspace"><div className="knowledge-list-panel"><div className="data-table knowledge-management-table"><div className="table-head knowledge-cols"><input aria-label="全选当前页" type="checkbox" checked={items.length>0&&items.every(item=>checked.includes(item.id))} onChange={e=>setChecked(e.target.checked?items.map(item=>item.id):[])}/><span>名称</span><span>所属文集</span><span>章节</span><span>题目数</span><span>状态</span></div>
      {items.map(item=>{const bookText=item.books.length===0?"未加入文集":item.books.length>2?`${item.books.length} 个文集`:item.books.map(book=>book.bookName).join("、");const chapterText=item.books.length?item.books.map(book=>book.chapterName).filter((value,index,array)=>array.indexOf(value)===index).join("、"):"—";return <div className="table-row knowledge-cols" key={item.id}><input aria-label={`选择 ${item.name}`} type="checkbox" checked={checked.includes(item.id)} onChange={()=>toggle(item.id)}/><button className="knowledge-open" onClick={()=>setSelected(item)}><b>{item.name}</b></button><span className={item.books.length?"":"unassigned-tag"}>{bookText}</span><span>{chapterText}</span><span>{item.questionCount}</span><i>{manageLabel("knowledgeStatus",item.status)}</i></div>})}</div>{pager}</div>
      <aside className="detail-panel">{selected?<KnowledgeEditor key={selected.id+selected.revision} point={selected} editable={editable} admin={admin} fail={fail} saved={point=>{setSelected(point);load()}}/>:<Empty>选择一条知识点查看详情</Empty>}</aside></div>
  </section>;
}

function knowledgeMembershipLabel(point: KnowledgeView) {
  if (!point.books.length) return "未加入文集";
  if (point.books.length > 1) return `${point.books.length} 个文集`;
  return `${point.books[0].bookName} / ${point.books[0].chapterName}`;
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
  return <div className="editor"><header><b>{point.name}</b><span>{point.books.length?point.books.map(book=>`${book.bookName} / ${book.chapterName}`).join("；"):"未加入文集"}</span></header>
    <label>名称<input disabled={!editable} value={point.name} onChange={(e) => setPoint({ ...point, name: e.target.value })} /></label>
    <div className="form-row"><label>默认角色<select disabled={!editable} value={point.defaultRole} onChange={(e) => setPoint({ ...point, defaultRole: e.target.value as KnowledgeView["defaultRole"] })}>{manageOptions("knowledgeRole").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
      <label>状态<select disabled={!editable} value={point.status} onChange={(e) => setPoint({ ...point, status: e.target.value as KnowledgeView["status"] })}>{manageOptions("knowledgeStatus").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label></div>
    <label>别名（每行一个）<textarea disabled={!editable} value={point.aliases.join("\n")} onChange={(e) => setPoint({ ...point, aliases: e.target.value.split("\n").filter(Boolean) })} /></label>
    <label>说明<textarea disabled={!editable} value={point.description} onChange={(e) => setPoint({ ...point, description: e.target.value })} /></label>
    <label>知识点解析（Markdown + LaTeX）<textarea disabled={!editable} rows={8} value={point.explanation} onChange={(e) => setPoint({ ...point, explanation: e.target.value })} /></label>
    <details><summary>技术信息</summary><dl className="technical-info"><dt>KnowledgePoint ID</dt><dd><code>{point.id}</code></dd><dt>知识点编码</dt><dd><code>{point.code}</code></dd><dt>revision</dt><dd>{point.revision}</dd></dl></details>
    {editable && <button disabled={busy} onClick={() => { setBusy(true); manageApi.saveKnowledge(point).then(saved).catch((e) => fail(e.message)).finally(() => setBusy(false)); }}>保存知识点</button>}
    {admin && point.status === "active" && <fieldset className="editor-group merge-panel"><legend>合并知识点</legend>
      <p>旧知识点会保留为已停用或已合并状态；题目关系、原知识点编码、名称和别名将迁移到目标知识点。</p>
      <div className="inline-search"><input placeholder="搜索目标知识点编码 / 名称 / 别名" value={targetQuery} onChange={(e) => setTargetQuery(e.target.value)} /><button onClick={() => manageApi.knowledge({ query: targetQuery, status: "active", size: 10 }).then((page) => setTargets(page.content.filter((item) => item.id !== point.id))).catch((error) => fail(error.message))}>搜索</button></div>
      {targets.length > 0 && <div className="merge-targets">{targets.map((item) => <button className={target?.id === item.id ? "selected" : ""} key={item.id} onClick={() => setTarget(item)}><code>{item.code}</code><span>{item.name}</span><small>{knowledgeMembershipLabel(item)}</small></button>)}</div>}
      {target && <p className="selected-target">目标：<code>{target.code}</code> {target.name}</p>}
      <label>合并原因<textarea rows={3} value={mergeReason} onChange={(event) => setMergeReason(event.target.value)} placeholder="记录口径重复、命名修订或知识体系调整原因" /></label>
      <button className="danger" disabled={busy || !target || !mergeReason.trim()} onClick={merge}>确认迁移并合并</button>
    </fieldset>}
  </div>;
}

function BooksManagementPage({ fail }: { fail: (value: string) => void }) {
  const [items, setItems] = useState<ManagedBook[]>([]);
  const [selected, setSelected] = useState<ManagedBookDetail>();
  const [creating,setCreating]=useState(false); const [newBook,setNewBook]=useState({name:"",description:"",enabled:true});
  const [newChapter,setNewChapter]=useState({name:"",description:""});
  const load = useCallback(() => manageApi.books().then(setItems).catch(error => fail(error.message)), [fail]);
  useEffect(() => { void load(); }, [load]);
  const open = (id: string) => manageApi.book(id).then(setSelected).catch(error => fail(error.message));
  const saveBook = () => selected && manageApi.saveBook(selected.book).then(value => { setSelected(value); void load(); }).catch(error => fail(error.message));
  const saveChapter = (chapter: ManagedChapter) => selected && manageApi.saveChapter(selected.book.id, chapter)
    .then(saved => setSelected({ ...selected, chapters: selected.chapters.map(item => item.id === saved.id ? saved : item) }))
    .catch(error => fail(error.message));
  const createBook=async()=>{if(!newBook.name.trim())return;try{const value=await manageApi.createBook(newBook);setSelected(value);setCreating(false);setNewBook({name:"",description:"",enabled:true});await load()}catch(error){fail((error as Error).message)}};
  const createChapter=async()=>{if(!selected||!newChapter.name.trim())return;try{await manageApi.createChapter(selected.book.id,newChapter);setNewChapter({name:"",description:""});setSelected(await manageApi.book(selected.book.id));await load()}catch(error){fail((error as Error).message)}};
  const deleteChapter=async(chapter:ManagedChapter)=>{if(!selected||!window.confirm(`确认删除章节“${chapter.name}”？\n\n删除章节会移除该章节及其知识点在本书中的归属关系，不会删除全局知识点和题目。`))return;try{await manageApi.deleteChapter(selected.book.id,chapter.id);setSelected(await manageApi.book(selected.book.id));await load()}catch(error){fail((error as Error).message)}};
  const moveChapter=async(chapter:ManagedChapter,offset:number)=>{if(!selected)return;const index=selected.chapters.findIndex(item=>item.id===chapter.id);const target=index+offset;if(target<0||target>=selected.chapters.length)return;const ordered=[...selected.chapters];[ordered[index],ordered[target]]=[ordered[target],ordered[index]];try{const chapters=await manageApi.reorderChapters(selected.book.id,ordered.map(item=>item.id));setSelected({...selected,chapters})}catch(error){fail((error as Error).message)}};
  const remove = async () => {
    if (!selected) return;
    const message = `确认删除文集“${selected.book.name}”？\n\n将删除该文集本身、章节结构和文集-知识点关系。\n不会删除全局知识点、全局题目和学习历史。`;
    if (!window.confirm(message)) return;
    try { await manageApi.deleteBook(selected.book.id); setSelected(undefined); await load(); }
    catch (error) { fail((error as Error).message); }
  };
  return <section><div className="page-title-actions"><PageTitle title="文集管理" detail="维护文集、章节和学习范围；稳定 ID 与章节编码由系统管理"/><button className="primary" onClick={()=>setCreating(value=>!value)}>新建文集</button></div>
    {creating&&<div className="manage-card create-book-form"><input placeholder="文集名称" value={newBook.name} onChange={e=>setNewBook({...newBook,name:e.target.value})}/><input placeholder="文集描述" value={newBook.description} onChange={e=>setNewBook({...newBook,description:e.target.value})}/><label className="inline-check"><input type="checkbox" checked={newBook.enabled} onChange={e=>setNewBook({...newBook,enabled:e.target.checked})}/>启用文集</label><button className="primary" disabled={!newBook.name.trim()} onClick={createBook}>创建文集</button></div>}
    <div className="split-workspace book-management"><div className="data-table"><div className="table-head book-cols"><span>文集</span><span>成员</span><span>可学习</span><span>题目</span><span>状态</span></div>
      {items.map(item => <button className="table-row book-cols" key={item.id} onClick={() => open(item.id)}><b>{item.name}</b><span>{item.membershipCount}</span><span>{item.trainableKnowledgePointCount}</span><span>{item.publishedQuestionCount}</span><i>{item.enabled ? "正常" : "已停用"}</i></button>)}</div>
      <aside className="detail-panel wide">{selected ? <div className="editor book-editor"><header><b>{selected.book.name}</b><span>{selected.book.membershipCount} 个成员 · {selected.book.trainableKnowledgePointCount} 个可学习知识点 · {selected.book.publishedQuestionCount} 道已发布题目</span></header>
        <label>文集名称<input value={selected.book.name} onChange={event => setSelected({ ...selected, book: { ...selected.book, name: event.target.value } })} /></label>
        <label>文集描述<textarea rows={4} value={selected.book.description} onChange={event => setSelected({ ...selected, book: { ...selected.book, description: event.target.value } })} /></label>
        <label className="inline-check"><input type="checkbox" checked={selected.book.enabled} onChange={event => setSelected({ ...selected, book: { ...selected.book, enabled: event.target.checked } })} />启用文集</label>
        <button className="primary" onClick={saveBook}>保存文集</button>
        <details><summary>技术信息</summary><dl className="technical-info"><dt>Book ID</dt><dd><code>{selected.book.id}</code></dd><dt>revision</dt><dd>{selected.book.revision}</dd></dl></details>
        <fieldset className="editor-group"><legend>章节</legend><div className="create-chapter-form"><input placeholder="新章节名称" value={newChapter.name} onChange={e=>setNewChapter({...newChapter,name:e.target.value})}/><input placeholder="章节描述" value={newChapter.description} onChange={e=>setNewChapter({...newChapter,description:e.target.value})}/><button disabled={!newChapter.name.trim()} onClick={createChapter}>+ 新建章节</button></div>{selected.chapters.map((chapter,index)=><ChapterEditor key={chapter.id} chapter={chapter} changed={next=>setSelected({...selected,chapters:selected.chapters.map(item=>item.id===next.id?next:item)})} save={()=>saveChapter(chapter)} moveUp={()=>moveChapter(chapter,-1)} moveDown={()=>moveChapter(chapter,1)} canUp={index>0} canDown={index<selected.chapters.length-1} remove={()=>deleteChapter(chapter)}/>)}</fieldset>
        <section className="danger-zone"><div><b>危险操作</b><p>删除后将移除该文集及章节结构和文集绑定关系，不会删除全局题目、知识点和学习历史。</p></div><button className="danger" onClick={remove}>删除文集</button></section>
      </div> : <Empty>选择一部文集查看详情</Empty>}</aside></div>
  </section>;
}

function ChapterEditor({chapter,changed,save,moveUp,moveDown,canUp,canDown,remove}:{chapter:ManagedChapter;changed:(chapter:ManagedChapter)=>void;save:()=>void;moveUp:()=>void;moveDown:()=>void;canUp:boolean;canDown:boolean;remove:()=>void}) {
  return <div className="chapter-editor"><div><b>{chapter.name}</b><details><summary>技术信息</summary><code>{chapter.id}</code><code>{chapter.code}</code><small>revision {chapter.revision}</small></details></div><label>章节名称<input value={chapter.name} onChange={event=>changed({...chapter,name:event.target.value})}/></label><label>章节描述<input value={chapter.description} onChange={event=>changed({...chapter,description:event.target.value})}/></label><div className="chapter-actions"><button disabled={!canUp} onClick={moveUp}>↑</button><button disabled={!canDown} onClick={moveDown}>↓</button><button onClick={save}>保存</button><button className="danger" onClick={remove}>删除</button></div></div>;
}

export function SourcePage({ fail }: { fail: (value: string) => void }) {
  const empty = { sourceType: "custom", canonicalName: "", displayName: "", status: "active" } as const;
  const [query,setQuery]=useState(""); const [sourceType,setSourceType]=useState(""); const [status,setStatus]=useState("");
  const [items,setItems]=useState<QuestionSourceView[]>([]); const [selected,setSelected]=useState<QuestionSourceView|null>(null);
  const [draft,setDraft]=useState<Omit<QuestionSourceView,"id"|"revision"|"questionCount"|"updatedAt">>(empty);
  const [page,setPage]=useState(0); const [totalPages,setTotalPages]=useState(0); const [total,setTotal]=useState(0);
  const load=useCallback(()=>manageApi.sources({query,sourceType,status,page,size:PAGE_SIZE}).then(result=>{setItems(result.content);setTotal(result.totalElements);setTotalPages(result.totalPages);if(page>0&&!result.content.length)setPage(page-1)}).catch(error=>fail(error.message)),[query,sourceType,status,page,fail]);
  useEffect(()=>{void load()},[load]);
  const edit=selected||draft; const changed=(next:typeof edit)=>selected?setSelected(next as QuestionSourceView):setDraft(next);
  const save=async()=>{try{const value=selected?await manageApi.saveSource(selected):await manageApi.createSource(draft);setSelected(value);setDraft(empty);await load()}catch(error){fail((error as Error).message)}};
  return <section><PageTitle title="来源管理" detail={`共 ${total} 个全局题目来源`} /><div className="manage-toolbar source-filters"><input placeholder="搜索展示名称 / 正式名称" value={query} onChange={e=>{setQuery(e.target.value);setPage(0)}}/><select value={sourceType} onChange={e=>{setSourceType(e.target.value);setPage(0)}}><option value="">全部类型</option>{manageOptions("source").map(option=><option key={option.value} value={option.value}>{option.label}</option>)}</select><select value={status} onChange={e=>{setStatus(e.target.value);setPage(0)}}><option value="">全部状态</option><option value="active">启用</option><option value="disabled">停用</option></select><button onClick={()=>{setSelected(null);setDraft(empty)}}>新建来源</button></div>
    <div className="split-workspace"><div className="data-table"><div className="table-head source-cols"><span>展示名称</span><span>正式名称</span><span>类型</span><span>状态</span><span>题目数</span><span>更新时间</span></div>{items.map(item=><button className="table-row source-cols" key={item.id} onClick={()=>setSelected(item)}><b>{item.displayName}</b><span>{item.canonicalName}</span><span>{manageLabel("source",item.sourceType)}</span><i>{item.status==="active"?"启用":"停用"}</i><span>{item.questionCount}</span><time>{new Date(item.updatedAt).toLocaleDateString("zh-CN")}</time></button>)}<div className="manage-pagination"><span>第 {totalPages?page+1:0} / {totalPages} 页</span><div><button disabled={page===0} onClick={()=>setPage(page-1)}>上一页</button><button disabled={!totalPages||page>=totalPages-1} onClick={()=>setPage(page+1)}>下一页</button></div></div></div>
      <aside className="detail-panel"><div className="editor"><header><b>{selected?"编辑来源":"新建来源"}</b>{selected&&<span>修订版本 {selected.revision}</span>}</header><label>展示名称<input value={edit.displayName} onChange={e=>changed({...edit,displayName:e.target.value})}/></label><label>正式名称<input value={edit.canonicalName} onChange={e=>changed({...edit,canonicalName:e.target.value})}/></label><label>来源类型<select value={edit.sourceType} onChange={e=>changed({...edit,sourceType:e.target.value as QuestionSourceView["sourceType"]})}>{manageOptions("source").map(option=><option key={option.value} value={option.value}>{option.label}</option>)}</select></label><label>状态<select value={edit.status} onChange={e=>changed({...edit,status:e.target.value as QuestionSourceView["status"]})}><option value="active">启用</option><option value="disabled">停用</option></select></label><button className="primary" disabled={!edit.canonicalName.trim()||!edit.displayName.trim()} onClick={save}>保存来源</button></div></aside></div>
  </section>;
}

export function QuestionPage({ user, fail, reviewOnly = false, initialQuestionId }: { user: ManageUser; fail: (v: string) => void; reviewOnly?: boolean; initialQuestionId?: string }) {
  const [query,setQuery]=useState(""); const [questionType,setQuestionType]=useState(""); const [items,setItems]=useState<QuestionView[]>([]); const [selected,setSelected]=useState<QuestionView|null>(null); const [creating,setCreating]=useState(false); const [checked,setChecked]=useState<string[]>([]); const [page,setPage]=useState(0); const [totalPages,setTotalPages]=useState(0); const [totalElements,setTotalElements]=useState(0);
  // 保存成功提示由 QuestionPage 持有：新建成功后 QuestionEditor 的 key 会从 "new" 变成题目 ID
  // 并重新挂载，放在子组件 state 里的提示会瞬间丢失。
  const [notice,setNotice]=useState<{message:string;version:number}|null>(null); const [editorRevision,setEditorRevision]=useState(0);
  const [bulkRejecting,setBulkRejecting]=useState(false); const [bulkComment,setBulkComment]=useState(""); const [bulkBusy,setBulkBusy]=useState(false);
  // 普通题目页按题型筛选，不再发送 status；审核中心仍固定 status=pending_review。
  const load=useCallback(()=>manageApi.questions({query,questionType,status:reviewOnly?"pending_review":undefined,page,size:PAGE_SIZE}).then(result=>{setItems(result.content);setTotalPages(result.totalPages);setTotalElements(result.totalElements);setChecked([]);if(page>0&&!result.content.length)setPage(page-1)}).catch(e=>fail(e.message)),[query,questionType,reviewOnly,page,fail]);
  useEffect(()=>{setPage(0)},[reviewOnly]);
  useEffect(()=>{void load()},[load]);
  useEffect(()=>{if(initialQuestionId&&!reviewOnly)manageApi.question(initialQuestionId).then(setSelected).catch(e=>fail(e.message))},[initialQuestionId,reviewOnly,fail]);
  useEffect(()=>{if(!notice)return;const timeout=window.setTimeout(()=>setNotice(null),3000);return()=>window.clearTimeout(timeout)},[notice]);
  const showNotice=(message:string)=>setNotice(current=>({message,version:(current?.version||0)+1}));
  const startEdit=(item:QuestionView)=>{setNotice(null);manageApi.question(item.id).then(setSelected).catch(e=>fail(e.message))};
  const startCreate=()=>{setNotice(null);setSelected(null);setCreating(true)};
  const closeEditor=()=>{setSelected(null);setCreating(false)};
  const toggle=(id:string)=>setChecked(values=>values.includes(id)?values.filter(value=>value!==id):[...values,id]);
  const remove=async()=>{if(!checked.length||!window.confirm(`确认永久删除所选 ${checked.length} 道题？历史学习事实仍会保留。`))return;try{const result=await manageApi.bulkDeleteQuestions(checked);setSelected(null);setChecked([]);await load();window.alert(`已删除 ${result.deleted} 道题。`)}catch(error){fail((error as Error).message)}};
  const exportRemedial=async()=>{if(!checked.length)return;try{const value=await manageApi.exportRemedialSource({questionIds:checked});downloadJson("remedial-question-generation.json",value)}catch(error){fail((error as Error).message)}};
  const pager=<div className="manage-pagination"><span>{reviewOnly?`待审核共 ${totalElements} 道`:`共 ${totalElements} 道`}</span><span>第 {totalPages? page+1:0} / {totalPages} 页</span><div><button disabled={page===0} onClick={()=>setPage(page-1)}>上一页</button><button disabled={!totalPages||page>=totalPages-1} onClick={()=>setPage(page+1)}>下一页</button></div></div>;
  const canReviewItem=(item:QuestionView)=>user.roles.includes("ADMIN")||(user.roles.includes("REVIEWER")&&item.createdBy!==user.id);
  const selectable=items.filter(canReviewItem);
  const runBulkReview=async(approve:boolean)=>{const chosen=items.filter(item=>checked.includes(item.id));if(!chosen.length)return;if(approve&&!window.confirm(`确认审核通过并发布所选 ${chosen.length} 道题？`))return;try{setBulkBusy(true);const result=await manageApi.bulkReviewQuestions(chosen,approve,approve?"批量审核通过并发布":bulkComment.trim());setChecked([]);setSelected(null);setBulkRejecting(false);setBulkComment("");await load();window.alert(approve?`已审核通过 ${result.approved} 道题。`:`已退回 ${result.rejected} 道题。`)}catch(error){fail((error as Error).message)}finally{setBulkBusy(false)}};
  const toast=<div className="manage-toast-host">{notice&&<div className="manage-toast" role="status" aria-live="polite" aria-atomic="true">{notice.message}</div>}</div>;
  if(reviewOnly)return <>{toast}<section className="review-page"><PageTitle title="审核中心" detail="只处理待审核题目的发布或退回"/><div className="manage-toolbar"><input placeholder="搜索题干 / 来源 / 题号" value={query} onChange={e=>{setQuery(e.target.value);setPage(0)}}/><button onClick={load}>查询</button></div><div className="review-workspace"><div className="question-list-panel"><div className="data-table manage-question-list"><div className="table-head review-cols"><input aria-label="全选当前页可审核题目" type="checkbox" disabled={!selectable.length} checked={selectable.length>0&&selectable.every(item=>checked.includes(item.id))} onChange={e=>setChecked(e.target.checked?selectable.map(item=>item.id):[])}/><span>来源</span><span>题型</span><span>创建者</span><span>状态</span></div>{items.map(item=>{const allowed=canReviewItem(item);return <div className="table-row review-cols" key={item.id}><input aria-label={`选择 ${item.id}`} title={allowed?"选择这道题":"不能审核自己创建的题目"} type="checkbox" disabled={!allowed} checked={checked.includes(item.id)} onChange={()=>toggle(item.id)}/><button className="review-question-link" onClick={()=>startEdit(item)}><b>{item.examYear?`${item.examYear} · ${item.displayQuestionNumber||item.questionNumber||""}`.trim():item.displayQuestionNumber||item.questionNumber||item.sourceName||"自建题"}</b><span>{manageLabel("questionType",item.questionType)}</span><span>{item.creatorName||"未知 / 已删除用户"}</span><i>{manageLabel("questionStatus",item.status)}</i></button></div>})}</div><div className="review-list-footer"><div className="bulk-review-actions"><span>已选 {checked.length} 道</span><button className="approve" disabled={!checked.length||bulkBusy} onClick={()=>runBulkReview(true)}>批量审核通过（{checked.length}）</button><button className="reject" disabled={!checked.length||bulkBusy} onClick={()=>setBulkRejecting(true)}>批量退回（{checked.length}）</button></div>{bulkRejecting&&<div className="bulk-reject-form"><textarea autoFocus rows={2} value={bulkComment} onChange={event=>setBulkComment(event.target.value)} placeholder="统一退回原因"/><button onClick={()=>{setBulkRejecting(false);setBulkComment("")}}>取消</button><button className="reject" disabled={!bulkComment.trim()||bulkBusy} onClick={()=>runBulkReview(false)}>确认批量退回</button></div>}{pager}</div></div><aside className="review-detail-panel">{selected?<QuestionEditor key={selected.id+selected.revision} initial={selected} user={user} reviewMode fail={fail} saved={(value)=>{showNotice(`审核完成：${value.sourceName||"题目"} ${value.examYear?`${value.examYear} · ${value.questionNumber||""}`:""}`.trim());setSelected(null);void load()}}/>:<Empty>选择待审核题目查看详情</Empty>}</aside></div></section></>;
  return <>{toast}<section className="question-management-page"><PageTitle title="题目管理" detail="全状态题目的创建、编辑、筛选、分页与批量删除"/><div className="manage-toolbar question-management-toolbar"><input placeholder="搜索题干 / 来源 / 题号" value={query} onChange={e=>{setQuery(e.target.value);setPage(0)}}/><select aria-label="题型" value={questionType} onChange={e=>{setQuestionType(e.target.value);setPage(0)}}><option value="">全部题型</option>{manageOptions("questionType").map(option=><option key={option.value} value={option.value}>{option.label}</option>)}</select><button onClick={load}>查询</button><button className="primary" onClick={startCreate}>新建题目</button><button disabled={!checked.length} onClick={exportRemedial}>导出子题生成包</button><button className="danger" disabled={!checked.length} onClick={remove}>批量删除（{checked.length}）</button></div>
    <div className="question-management-workspace"><div className="question-management-table data-table"><div className="table-head question-management-cols"><input aria-label="全选当前页" type="checkbox" checked={items.length>0&&items.every(item=>checked.includes(item.id))} onChange={e=>setChecked(e.target.checked?items.map(item=>item.id):[])}/><span>来源</span><span>题号</span><span>科目</span><span>题型</span><span>难度</span><span>状态</span><span>知识点</span><span>更新时间</span><span>操作</span></div>{items.map(item=><div className="table-row question-management-cols" key={item.id}><input aria-label={`选择 ${item.id}`} type="checkbox" checked={checked.includes(item.id)} onChange={()=>toggle(item.id)}/><span className="question-cell-clamp" title={item.sourceName||manageLabel("source",item.sourceType)}>{item.sourceName||manageLabel("source",item.sourceType)}</span><span>{item.examYear?`${item.examYear}-${item.displayQuestionNumber||item.questionNumber}`:item.displayQuestionNumber||item.questionNumber||"—"}</span><span>{item.subject}</span><span>{manageLabel("questionType",item.questionType)}</span><span>{item.difficulty}</span><i>{manageLabel("questionStatus",item.status)}</i><span className="question-cell-clamp" title={item.knowledgePoints.map(point=>point.name).join("、")}>{item.knowledgePoints.map(point=>point.name).join("、")}</span><time>{item.updatedAt ? new Date(item.updatedAt).toLocaleDateString("zh-CN") : "—"}</time><button onClick={()=>startEdit(item)}>编辑</button></div>)}</div>{pager}</div>
    {(selected||creating)&&<div className="question-editor-drawer" role="dialog" aria-modal="true"><div className="drawer-backdrop" onClick={closeEditor}/><aside><button className="drawer-close" onClick={closeEditor}>关闭</button><QuestionEditor key={(selected?.id||"new")+"#"+editorRevision} initial={selected} user={user} fail={fail} saved={q=>{showNotice(selected?`已保存修改：${q.sourceName||"题目"}`:`草稿已创建：${q.sourceName||"题目"}`);setSelected(q);setCreating(false);setEditorRevision(value=>value+1);load()}}/></aside></div>}
  </section></>;
}

function QuestionEditor({ initial, user, fail, saved, reviewMode = false }: { initial: QuestionView | null; user: ManageUser; fail: (v: string) => void; saved: (v: QuestionView) => void; reviewMode?: boolean }) {
  const [question, setQuestion] = useState<Partial<QuestionView>>(initial || { subject: "数学一", questionType: "single_choice", presentationType: "single_choice", gradingMode: "auto", difficulty: 2, options: [], knowledgePoints: [] });
  const [knowledgeQuery, setKnowledgeQuery] = useState(""); const [matches, setMatches] = useState<KnowledgeView[]>([]); const [busy, setBusy] = useState(false);
  const [sourceQuery,setSourceQuery]=useState(""); const [sourceMatches,setSourceMatches]=useState<QuestionSourceView[]>([]);
  const [rejecting, setRejecting] = useState(false); const [rejectComment, setRejectComment] = useState("");
  // 题干图片只改编辑器本地 state：上传 / 替换 / 移除都不自动保存 Question，
  // 仍需用户显式点击“保存草稿 / 保存修改”。
  const [imageBusy, setImageBusy] = useState(false); const imageInput = useRef<HTMLInputElement>(null);
  const isAdmin = user.roles.includes("ADMIN");
  const isReviewer = user.roles.includes("REVIEWER");
  const canReview = isAdmin || (isReviewer && user.id !== initial?.createdBy);
  const addOption = () => setQuestion({ ...question, options: [...(question.options || []), { key: String.fromCharCode(65 + (question.options?.length || 0)), text: "", correct: false, sortOrder: question.options?.length || 0 }] });
  const moveKnowledge = (index: number, offset: number) => {
    const relations = [...(question.knowledgePoints || [])];
    const destination = index + offset;
    if (destination < 0 || destination >= relations.length) return;
    [relations[index], relations[destination]] = [relations[destination], relations[index]];
    setQuestion({ ...question, knowledgePoints: relations.map((relation, sortOrder) => ({ ...relation, sortOrder })) });
  };
  const save = async () => { try { setBusy(true); const result = initial ? await manageApi.saveQuestion(question as QuestionView) : await manageApi.createQuestion(question); saved(result); } catch (e) { fail(e instanceof Error ? e.message : "保存失败"); } finally { setBusy(false); } };
  const pickImage = () => { if (imageInput.current) { imageInput.current.value = ""; imageInput.current.click(); } };
  const uploadImage = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    try { setImageBusy(true); const asset = await manageApi.uploadQuestionImage(file); setQuestion(current => ({ ...current, stemImageId: asset.id, stemImageUrl: asset.url })); }
    catch (e) { fail(e instanceof Error ? e.message : "图片上传失败"); }
    finally { setImageBusy(false); }
  };
  const setStemImage = (stemImageId: string | null, stemImageUrl: string | null) => setQuestion(current => ({ ...current, stemImageId, stemImageUrl }));
  const stemImageField = <fieldset className="editor-group stem-image-group"><legend>题目图片（可选）</legend>
    {question.stemImageUrl ? <div className="stem-image-current"><QuestionStemImage src={question.stemImageUrl} /><div className="stem-image-actions"><button disabled={imageBusy} onClick={pickImage}>{imageBusy ? "上传中…" : "替换图片"}</button><button className="danger" disabled={imageBusy} onClick={() => setStemImage(null, null)}>移除图片</button></div></div>
      : <div className="stem-image-empty"><button disabled={imageBusy} onClick={pickImage}>{imageBusy ? "上传中…" : "上传图片"}</button><small>支持 PNG / JPEG，不超过 5MB。</small></div>}
    <input ref={imageInput} className="stem-image-input" type="file" accept="image/png,image/jpeg" aria-label="上传题目图片" onChange={uploadImage} />
  </fieldset>;
  const editorFields = <><header><b>{initial ? `题目 ${initial.id.slice(0, 8)}` : "新建全服题目草稿"}</b><span>{manageLabel("questionStatus", question.status || "draft")} {question.revision ? `· 修订版本 ${question.revision}` : ""}</span></header>
    <fieldset className="editor-group"><legend>来源</legend><div className="selected-source"><b>{question.sourceName||"尚未选择来源"}</b><span>{question.sourceType?manageLabel("source",question.sourceType):"—"}</span></div><div className="inline-search"><input placeholder="搜索已有来源" value={sourceQuery} onChange={e=>setSourceQuery(e.target.value)}/><button onClick={()=>manageApi.sources({query:sourceQuery,status:"active",size:10}).then(result=>setSourceMatches(result.content)).catch(error=>fail(error.message))}>搜索</button></div>{sourceMatches.length>0&&<div className="search-results">{sourceMatches.map(source=><button key={source.id} onClick={()=>setQuestion({...question,sourceId:source.id,sourceType:source.sourceType,sourceName:source.displayName,sourceCanonicalName:source.canonicalName})}>{source.displayName} · {manageLabel("source",source.sourceType)}</button>)}</div>}<small>新来源请先到“来源管理”创建；来源类型由所选来源决定。</small></fieldset>
    <div className="form-grid"><label>科目<input value={question.subject || ""} onChange={(e) => setQuestion({ ...question, subject: e.target.value })} /></label><label>年份<input type="number" value={question.examYear || ""} onChange={(e) => setQuestion({ ...question, examYear: Number(e.target.value) || undefined })} /></label><label>题号<input value={question.questionNumber || ""} onChange={(e) => setQuestion({ ...question, questionNumber: e.target.value })} /></label><label>难度<select value={question.difficulty} onChange={(e) => setQuestion({ ...question, difficulty: Number(e.target.value) })}>{[1,2,3,4,5].map((n) => <option key={n}>{n}</option>)}</select></label>
      <label>题型<select value={question.questionType} onChange={(e) => { const questionType=e.target.value; setQuestion({ ...question, questionType, ...questionTypeContract(questionType), options: questionType === "solution" ? [] : question.options }); }}>{question.questionType==="blank"&&<option value="blank" disabled>历史填空题（必须改选）</option>}{manageOptions("questionType").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label><label>作答方式<select value={question.presentationType} disabled>{manageOptions("presentation").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label><label>判题模式<select value={question.gradingMode} disabled>{manageOptions("grading").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label></div>
    <label>题干（Markdown + LaTeX）<textarea rows={8} value={question.content || ""} onChange={(e) => setQuestion({ ...question, content: e.target.value })} /></label>{stemImageField}<details open><summary>预览题干</summary><div className="markdown-preview"><RichText>{question.content || "暂无题干"}</RichText><QuestionStemImage src={question.stemImageUrl} /></div></details>
    <label>{question.questionType === "solution" ? "完整解析（包含答案与解析）" : "完整解析"}<textarea rows={7} value={question.analysis || ""} onChange={(e) => setQuestion({ ...question, analysis: e.target.value })} /></label><details open><summary>预览解析</summary><div className="markdown-preview"><RichText>{question.analysis || "暂无解析"}</RichText></div></details>
    {question.questionType !== "solution" && <fieldset className="editor-group"><legend>游戏化选项</legend>{(question.options || []).map((option, index) => <OptionRow key={index} option={option} changed={(next) => setQuestion({ ...question, options: question.options!.map((old, i) => i === index ? next : old) })} remove={() => setQuestion({ ...question, options: question.options!.filter((_, i) => i !== index) })} />)}<button onClick={addOption}>添加选项</button></fieldset>}
    <fieldset className="editor-group"><legend>知识点绑定</legend><div className="inline-search"><input placeholder="搜索知识点编码 / 名称 / 别名" value={knowledgeQuery} onChange={(e) => setKnowledgeQuery(e.target.value)} /><button onClick={() => manageApi.knowledge({ query: knowledgeQuery, status: "active", size: 10 }).then((p) => setMatches(p.content)).catch((e) => fail(e.message))}>搜索</button></div>
      {matches.length > 0 && <div className="search-results">{matches.map((point) => <button key={point.id} onClick={() => { if (!(question.knowledgePoints || []).some((r) => (r.id || r.knowledgePointId) === point.id)) setQuestion({ ...question, knowledgePoints: [...(question.knowledgePoints || []), { id: point.id, knowledgePointId: point.id, code: point.code, name: point.name, role: "core", sortOrder: question.knowledgePoints?.length || 0 }] }); }}>{point.code} {point.name}</button>)}</div>}
      <div className="relations">{(question.knowledgePoints || []).map((relation, index) => <div key={relation.id || relation.knowledgePointId}><span><code>{relation.code}</code> {relation.name}</span><select value={relation.role} onChange={(e) => setQuestion({ ...question, knowledgePoints: question.knowledgePoints!.map((r, i) => i === index ? { ...r, role: e.target.value as QuestionRelation["role"] } : r) })}><option value="core">核心</option><option value="auxiliary">辅助</option></select><span className="relation-order"><button aria-label="上移知识点" disabled={index === 0} onClick={() => moveKnowledge(index, -1)}>↑</button><button aria-label="下移知识点" disabled={index === (question.knowledgePoints?.length || 0) - 1} onClick={() => moveKnowledge(index, 1)}>↓</button></span><button onClick={() => setQuestion({ ...question, knowledgePoints: question.knowledgePoints!.filter((_, i) => i !== index) })}>解绑</button></div>)}</div>
    </fieldset>
    {question.reviewComment && <p className="review-comment">审核意见：{question.reviewComment}</p>}</>;
  if (reviewMode) return <div className="review-editor"><div className="review-detail-scroll"><div className="editor question-editor">{editorFields}</div></div><footer className="review-action-footer">
    {rejecting && canReview && <div className="review-decision"><label>退回修改原因<textarea autoFocus rows={3} value={rejectComment} onChange={event => setRejectComment(event.target.value)} placeholder="请明确写出需要修改的内容" /></label><div><button onClick={() => setRejecting(false)}>取消</button><button className="reject" disabled={!rejectComment.trim()} onClick={() => initial && manageApi.reviewQuestion(initial, false, rejectComment.trim()).then(saved).catch((e) => fail(e.message))}>确认退回修改</button></div></div>}
    {initial?.status === "pending_review" && canReview ? <div className="review-action-buttons"><button className="approve" onClick={() => manageApi.reviewQuestion(initial, true, "审核通过并发布").then(saved).catch((e) => fail(e.message))}>审核通过并发布</button><button className="reject" onClick={() => setRejecting(true)}>退回修改</button></div> : <p>当前账号不能审核这道题。</p>}
  </footer></div>;
  return <div className="editor question-editor">{editorFields}<div className="editor-actions"><button className="primary" disabled={busy} onClick={save}>{initial ? "保存修改" : "保存草稿"}</button>{initial && ["draft","rejected"].includes(initial.status) && <button onClick={() => manageApi.submitQuestion(initial).then(saved).catch((e) => fail(e.message))}>提交审核</button>}</div></div>;
}

function OptionRow({ option, changed, remove }: { option: QuestionOption; changed: (v: QuestionOption) => void; remove: () => void }) { return <div className="option-edit"><input className="option-key" value={option.key} onChange={(e) => changed({ ...option, key: e.target.value })} /><input value={option.text} onChange={(e) => changed({ ...option, text: e.target.value })} /><label><input type="checkbox" checked={option.correct} onChange={(e) => changed({ ...option, correct: e.target.checked })} />正确</label><button onClick={remove}>删除</button></div>; }

function AuditPage({ fail }: { fail: (value: string) => void }) {
  const [action, setAction] = useState("");
  const [entityType, setEntityType] = useState("");
  const [actor, setActor] = useState("");
  const [items, setItems] = useState<AuditLogView[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const load = useCallback(() => manageApi.auditLogs({ action, entityType, actor, page, size: PAGE_SIZE })
    .then((result) => { setItems(result.content); setTotal(result.totalElements); setTotalPages(result.totalPages); if(page>0&&!result.content.length)setPage(page-1); })
    .catch((error) => fail(error.message)), [action, entityType, actor, page, fail]);
  useEffect(() => { void load(); }, [load]);
  return <section><PageTitle title="审计记录" detail={`共 ${total} 条；只读展示全服内容与权限变更`} />
    <div className="manage-toolbar audit-filters"><input placeholder="操作者账号 / 名称 / UUID" value={actor} onChange={(event) => {setActor(event.target.value);setPage(0)}} />
      <select value={action} onChange={(event) => {setAction(event.target.value);setPage(0)}}><option value="">全部动作</option><option value="SOURCE_CREATED">来源创建</option><option value="SOURCE_UPDATED">来源修改</option><option value="KNOWLEDGE_UPDATED">知识点修改</option><option value="KNOWLEDGE_ALIASES_UPDATED">别名修改</option><option value="KNOWLEDGE_MERGED">知识点合并</option><option value="BOOK_UPDATED">文集修改</option><option value="BOOK_CHAPTER_UPDATED">章节修改</option><option value="BOOK_DELETED">文集删除</option><option value="QUESTION_CREATED">题目创建</option><option value="QUESTION_UPDATED">题目修改</option><option value="QUESTION_SUBMITTED">提交审核</option><option value="QUESTION_REVIEW_APPROVED">审核通过</option><option value="QUESTION_REVIEW_REJECTED">审核退回</option><option value="QUESTION_ARCHIVED">题目归档</option><option value="USER_ROLE_UPDATED">用户权限修改</option><option value="USER_DELETED">用户删除</option></select>
      <select value={entityType} onChange={(event) => {setEntityType(event.target.value);setPage(0)}}><option value="">全部实体</option><option value="question_source">题目来源</option><option value="knowledge_point">知识点</option><option value="question">题目</option><option value="learner_account">用户</option><option value="question_bank">文集</option></select>
      <button onClick={load}>查询</button></div>
    <div className="audit-list">{items.map((item) => <article key={item.id} className="audit-row"><header><b>{auditAction(item.action)}</b><time>{new Date(item.createdAt).toLocaleString("zh-CN", { hour12: false })}</time></header>
      <p><span>{item.actorDisplayName || "系统"}</span>{item.actorUsername && <code>{item.actorUsername}</code>} · {manageLabel("entity", item.entityType)} · <code>{item.entityId}</code></p>
      <details><summary>查看技术详情</summary><pre>{JSON.stringify(item.metadata, null, 2)}</pre></details></article>)}{items.length === 0 && <Empty>暂无符合条件的审计记录</Empty>}</div>
    <div className="manage-pagination audit-pagination"><span>共 {total} 条</span><span>第 {totalPages?page+1:0} / {totalPages} 页</span><div><button disabled={page===0} onClick={()=>setPage(page-1)}>上一页</button><button disabled={!totalPages||page>=totalPages-1} onClick={()=>setPage(page+1)}>下一页</button></div></div>
  </section>;
}

function auditAction(action: string) {
  const labels: Record<string, string> = {
    SOURCE_CREATED: "来源创建", SOURCE_UPDATED: "来源修改",
    KNOWLEDGE_UPDATED: "知识点修改", KNOWLEDGE_ALIASES_UPDATED: "知识点别名修改", KNOWLEDGE_MERGED: "知识点合并",
    BOOK_UPDATED: "文集修改", BOOK_CHAPTER_UPDATED: "章节修改", BOOK_DELETED: "文集删除",
    QUESTION_CREATED: "题目创建", QUESTION_UPDATED: "题目修改", QUESTION_SUBMITTED: "题目提交审核",
    QUESTION_REVIEW_APPROVED: "题目审核通过", QUESTION_REVIEW_REJECTED: "题目审核退回", QUESTION_ARCHIVED: "题目归档",
    USER_CREATED: "用户创建", USER_ROLE_UPDATED: "用户权限修改", USER_DELETED: "用户删除", QUESTION_BANK_IMPORTED: "题库导入",
  };
  return labels[action] || action;
}

function UsersPage({ currentUser, fail }: { currentUser: ManageUser; fail: (v: string) => void }) {
  const [users, setUsers] = useState<ManageUser[]>([]); const [draft, setDraft] = useState({ username: "", displayName: "", password: "", roles: [] as string[] });
  const load = useCallback(() => manageApi.users().then(setUsers).catch((e) => fail(e.message)), [fail]); useEffect(() => { void load(); }, [load]);
  return <section><PageTitle title="用户权限" detail="所有学习者使用同一个账号；后台角色可以为空" /><div className="manage-card create-user"><h2>创建学习账号</h2><input placeholder="用户名" value={draft.username} onChange={(e) => setDraft({ ...draft, username: e.target.value })} /><input placeholder="显示名" value={draft.displayName} onChange={(e) => setDraft({ ...draft, displayName: e.target.value })} /><input type="password" placeholder="初始密码（至少10位）" value={draft.password} onChange={(e) => setDraft({ ...draft, password: e.target.value })} /><button onClick={() => manageApi.createUser(draft).then(() => { setDraft({ username: "", displayName: "", password: "", roles: [] }); load(); }).catch((e) => fail(e.message))}>创建用户</button></div>
    <div className="user-list">{users.map((user) => <UserRow key={user.id} user={user} currentUserId={currentUser.id} saved={load} fail={fail} />)}</div></section>;
}

function UserRow({ user: initial, currentUserId, saved, fail }: { user: ManageUser; currentUserId: string; saved: () => void; fail: (v: string) => void }) {
  const [user, setUser] = useState(initial);
  const remove=()=>{if(!window.confirm(`确定永久删除“${user.displayName}”吗？\n\n该用户的账号、登录会话、学习范围、世界进度、做题历史、掌握度、证据、专项和诊断数据都将被永久清除。\n\n全局题目、知识点和文集不会删除。`))return;manageApi.deleteUser(user.id).then(saved).catch(error=>fail(error.message))};
  return <div className="user-row"><div><b>{user.displayName}</b><code>{user.username}</code></div><select value={user.status} onChange={(e) => setUser({ ...user, status: e.target.value as ManageUser["status"] })}>{manageOptions("userStatus").map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select><div className="role-checks">{["CONTRIBUTOR","REVIEWER","ADMIN"].map((role) => <label key={role}><input type="checkbox" checked={user.roles.includes(role)} onChange={(e) => setUser({ ...user, roles: e.target.checked ? [...user.roles, role] : user.roles.filter((r) => r !== role) })} />{manageLabel("role", role)}</label>)}</div><div className="user-actions"><button onClick={() => manageApi.saveUser(user).then(saved).catch((e) => fail(e.message))}>保存</button><button className="danger" disabled={user.id===currentUserId} title={user.id===currentUserId?"不能删除当前登录账号":"永久删除用户及其学习数据"} onClick={remove}>删除用户</button></div></div>;
}

function Nav({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) { return <button className={active ? "active" : ""} onClick={onClick}>{children}</button>; }
function PageTitle({ title, detail }: { title: string; detail: string }) { return <header className="page-title"><div><h1>{title}</h1><p>{detail}</p></div></header>; }
function Metric({ label, value, note }: { label: string; value?: number; note: string }) { return <div className="metric"><span>{label}</span><b>{value ?? "—"}</b><small>{note}</small></div>; }
function Empty({ children }: { children: ReactNode }) { return <div className="empty-state">{children}</div>; }

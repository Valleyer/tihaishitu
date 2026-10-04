import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import App from "../App";
import { RichText } from "../components/RichText";
import { HttpError } from "../api/http";
import { platformApi, type BookDetail, type BrowseQuestion, type HubBootstrap, type KnowledgePoint, type KnowledgeState, type StudyProfile } from "./api";
import "./platform.css";

const go = (path: string) => window.location.assign(path);
const idAfter = (prefix: string) => decodeURIComponent(window.location.pathname.slice(prefix.length));
const flattenChapters = (chapters: BookDetail["chapters"]): BookDetail["chapters"] =>
  chapters.flatMap(chapter => [chapter, ...flattenChapters(chapter.children || [])]);

function AuthPage({ register }: { register: boolean }) {
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError("");
    try {
      if (register) await platformApi.register(username, displayName, password);
      else await platformApi.login(username, password);
      go("/");
    } catch (reason) { setError((reason as Error).message); }
    finally { setBusy(false); }
  };
  return <main className="auth-page">
    <form className="auth-card" onSubmit={submit}>
      <span className="hub-seal">题</span><p className="eyebrow">联机学习者</p>
      <h1>{register ? "建立学习身份" : "回到学习主世界"}</h1>
      <label>用户名<input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" required /></label>
      {register && <label>显示名称<input value={displayName} onChange={e => setDisplayName(e.target.value)} /></label>}
      <label>密码<input type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete={register ? "new-password" : "current-password"} minLength={8} required /></label>
      {error && <p className="hub-error" role="alert">{error}</p>}
      <button className="hub-primary" disabled={busy}>{busy ? "请稍候…" : register ? "注册并进入" : "登录"}</button>
      <a href={register ? "/login" : "/register"}>{register ? "已有账号，直接登录" : "第一次来？注册学习账号"}</a>
    </form>
  </main>;
}

function Shell({ data, children }: { data: HubBootstrap; children: React.ReactNode }) {
  return <div className="learning-hub">
    <header className="hub-header"><a className="hub-brand" href="/"><span>题</span>题海仕途</a>
      <nav><a href="/study">学习方向</a><a href="/books">文集与知识图谱</a><a href="/#worlds">游戏世界</a><a href="/account">{data.learner.displayName}</a></nav>
    </header>
    {children}
  </div>;
}

function HubHome({ data }: { data: HubBootstrap }) {
  const selected = data.bankManifest.filter(book => data.studyProfile.selectedBookIds.includes(book.id));
  return <Shell data={data}><main className="hub-main">
    <section className="hub-hero"><p className="eyebrow">Learning Hub · 学习主世界</p><h1>{data.learner.displayName}，今日从哪里继续？</h1>
      <p>文集决定完整学习范围，重点知识点只负责安排优先顺序。所有游戏世界共享同一份学习资源与学习身份。</p>
    </section>
    <div className="hub-grid">
      <section className="hub-panel"><div className="panel-heading"><div><small>MY STUDY</small><h2>我的学习</h2></div><a href="/study">调整学习方向</a></div>
        <p>当前模式：{data.studyProfile.focusMode === "manual" ? "手动重点" : "自动规划"} · {data.studyProfile.pace === "slow" ? "从容节奏" : "正常节奏"}</p>
        <div className="tag-row">{selected.map(book => <span key={book.id}>{book.name}</span>)}</div>
        <p>重点知识点：{data.studyProfile.focusedKnowledgePoints.map(point => point.name).join("、") || "由系统按可用知识范围自动安排"}</p>
      </section>
      <section className="hub-panel"><div className="panel-heading"><div><small>LIBRARY</small><h2>文集与知识图谱</h2></div><a href="/books">展开</a></div>
        <p>{data.bankManifest.length} 本可用文集，题目和知识点由服务器统一维护。浏览行为不会产生答题记录。</p>
      </section>
    </div>
    <section className="world-gallery" id="worlds"><div className="panel-heading"><div><small>WORLD GALLERY</small><h2>游戏世界</h2></div></div>
      <div className="world-cards">{data.worlds.map(world => <article key={world.id} className={world.enabled ? "world-card enabled" : "world-card"}>
        <p>{world.enabled ? "现已开放" : "筹备中"}</p><h3>{world.name}</h3><span>{world.description}</span>
        {world.enabled ? <a className="hub-primary" href={world.entryPath}>{world.initialized ? "继续旅程" : "初入此世"}</a> : <button disabled>尚未开放</button>}
      </article>)}</div>
    </section>
  </main></Shell>;
}

function StudyPage({ data, reload }: { data: HubBootstrap; reload: () => Promise<void> }) {
  const [profile, setProfile] = useState<StudyProfile>(data.studyProfile);
  const [selected, setSelected] = useState(data.studyProfile.selectedBookIds);
  const [focus, setFocus] = useState(data.studyProfile.focusedKnowledgePoints.map(p => p.id));
  const [details, setDetails] = useState<BookDetail[]>([]);
  const [message, setMessage] = useState("");
  useEffect(() => { Promise.all(data.bankManifest.map(book => platformApi.book(book.id))).then(setDetails).catch(e => setMessage(e.message)); }, [data.bankManifest]);
  const points = useMemo(() => {
    const values = details.filter(book => selected.includes(book.id)).flatMap(book => flattenChapters(book.chapters).flatMap(chapter => chapter.knowledgePoints));
    return [...new Map(values.map(point => [point.id, point])).values()];
  }, [details, selected]);
  const save = async () => {
    try { const updated = await platformApi.updateProfile(profile, selected, profile.focusMode === "manual" ? focus.filter(id => points.some(p => p.id === id)) : []); setProfile(updated); setMessage("学习方向已保存"); await reload(); }
    catch (reason) { setMessage((reason as Error).message); }
  };
  return <Shell data={data}><main className="hub-main narrow"><a href="/">← 返回主世界</a><h1>学习方向</h1>
    <section className="hub-panel"><h2>学习范围</h2><p>所选文集共同构成题目依赖和活动抽题的完整允许范围。</p>
      <div className="choice-list">{data.bankManifest.map(book => <label key={book.id}><input type="checkbox" checked={selected.includes(book.id)} onChange={() => setSelected(v => v.includes(book.id) ? v.filter(x => x !== book.id) : [...v, book.id])} /><span><b>{book.name}</b><small>{book.knowledgePointCount} 个知识点 · {book.questionCount} 道题</small></span></label>)}</div>
    </section>
    <section className="hub-panel"><h2>Study Focus</h2><div className="segmented"><button className={profile.focusMode === "auto" ? "active" : ""} onClick={() => setProfile({...profile, focusMode: "auto"})}>自动规划</button><button className={profile.focusMode === "manual" ? "active" : ""} onClick={() => setProfile({...profile, focusMode: "manual"})}>手动重点</button></div>
      <p>重点知识点只会优先进入训练计划，不会缩小依赖检查和学习范围。</p>
      {profile.focusMode === "manual" && <div className="knowledge-picker">{points.map(point => <label key={point.id}><input type="checkbox" checked={focus.includes(point.id)} onChange={() => setFocus(v => v.includes(point.id) ? v.filter(x => x !== point.id) : [...v, point.id])} /><span>{point.name}<small>{point.section} · {point.chapter}</small></span></label>)}</div>}
    </section>
    {message && <p className="hub-message">{message}</p>}<button className="hub-primary" onClick={save}>保存学习方向</button>
  </main></Shell>;
}

function BooksPage({ data }: { data: HubBootstrap }) { return <Shell data={data}><main className="hub-main narrow"><a href="/">← 返回主世界</a><h1>文集与知识图谱</h1><div className="book-list">{data.bankManifest.map(book => <a className="hub-panel" href={`/books/${book.id}`} key={book.id}><h2>{book.name}</h2><p>{book.description}</p><small>{book.knowledgePointCount} 个知识点 · {book.questionCount} 道已发布题目</small></a>)}</div></main></Shell>; }

const bandLabel: Record<KnowledgeState["band"], string> = { unstarted: "未开始", unmastered: "未掌握", learning: "学习中", ready: "基本掌握", proficient: "熟练掌握" };

function ChapterSection({ chapter, states }: { chapter: BookDetail["chapters"][number]; states: Map<string, KnowledgeState> }) {
  return <section className="hub-panel"><small>{chapter.code}</small><h2>{chapter.name}</h2><p>{chapter.description}</p><div className="knowledge-links">{chapter.knowledgePoints.map(point => { const state = states.get(point.id); return <a href={`/knowledge/${point.id}`} key={point.id}>{point.name}<small>{point.description}</small><span className={`mastery-band ${state?.band || "unstarted"}`}>{bandLabel[state?.band || "unstarted"]}{state?.evidenceCount ? ` · ${Math.round(state.effectiveMastery)}%` : ""}</span></a> })}</div>{chapter.children?.map(child => <ChapterSection chapter={child} states={states} key={child.id} />)}</section>;
}

function BookPage({ data, id }: { data: HubBootstrap; id: string }) {
  const [book, setBook] = useState<BookDetail>(); const [states, setStates] = useState(new Map<string, KnowledgeState>()); const [error, setError] = useState("");
  useEffect(() => { Promise.all([platformApi.book(id), platformApi.knowledgeStatesForBook(id)]).then(([value, stateList]) => { setBook(value); setStates(new Map(stateList.map(state => [state.knowledgePointId, state]))) }).catch(e => setError(e.message)); }, [id]);
  return <Shell data={data}><main className="hub-main narrow"><a href="/books">← 返回文集</a>{error && <p className="hub-error">{error}</p>}{book && <><h1>{book.name}</h1><p>{book.description}</p>{book.chapters.map(chapter => <ChapterSection chapter={chapter} states={states} key={chapter.id} />)}</>}</main></Shell>;
}

function KnowledgePage({ data, id }: { data: HubBootstrap; id: string }) {
  const [point, setPoint] = useState<(KnowledgePoint & { books: {id:string;name:string}[] })>(); const [questions, setQuestions] = useState<BrowseQuestion[]>([]); const [state, setState] = useState<KnowledgeState>(); const [error, setError] = useState("");
  useEffect(() => { Promise.all([platformApi.knowledge(id), platformApi.knowledgeQuestions(id), platformApi.knowledgeState(id)]).then(([p,q,s]) => {setPoint(p);setQuestions(q);setState(s)}).catch(e => setError(e.message)); }, [id]);
  return <Shell data={data}><main className="hub-main narrow"><a href="/books">← 返回知识图谱</a>{error && <p className="hub-error">{error}</p>}{point && <><p className="eyebrow">{point.subject} · {point.section} · {point.chapter}</p><h1>{point.name}</h1>{state && <section className="hub-panel mastery-summary"><h2>当前状态</h2>{state.evidenceCount === 0 ? <p>尚未开始正式训练</p> : <><p className="mastery-score"><b>{bandLabel[state.band]}</b> · {Math.round(state.effectiveMastery)}%</p><p>记忆稳定度：{state.stabilityDays.toFixed(1)} 天</p><p>目标难度：{state.targetDifficulty}</p><p>最近练习：{state.lastEvidenceAt ? new Date(state.lastEvidenceAt).toLocaleDateString("zh-CN") : "—"}</p></>}</section>}<section className="hub-panel rich"><RichText>{point.explanation || point.description}</RichText></section><h2>相关已发布题目</h2><div className="question-list">{questions.map(question => <a href={`/questions/${question.id}`} key={question.id}><span>{question.sourceName || question.sourceType}</span><b><RichText>{question.contentMarkdown}</RichText></b></a>)}</div></>}</main></Shell>;
}

function QuestionPage({ data, id }: { data: HubBootstrap; id: string }) {
  const [question, setQuestion] = useState<BrowseQuestion>(); const [error, setError] = useState(""); const [showAnswer, setShowAnswer] = useState(false);
  useEffect(() => { platformApi.question(id).then(setQuestion).catch(e => setError(e.message)); }, [id]);
  return <Shell data={data}><main className="hub-main narrow"><a href="/books">← 返回知识图谱</a>{error && <p className="hub-error">{error}</p>}{question && <><p className="eyebrow">只读题目浏览 · {question.sourceName}</p><h1>{question.subject}</h1><section className="hub-panel rich"><RichText>{question.contentMarkdown}</RichText>{question.options?.map(option => <p key={option.key}><b>{option.key}.</b> <RichText>{option.text}</RichText></p>)}</section><div className="tag-row">{question.knowledgePoints?.map(point => <a href={`/knowledge/${point.id}`} key={point.id}>{point.name}</a>)}</div><button className="hub-primary" onClick={() => setShowAnswer(v => !v)}>{showAnswer ? "收起答案与解析" : "查看答案与解析"}</button>{showAnswer && <section className="hub-panel rich"><h2>参考答案</h2><pre>{JSON.stringify(question.standardAnswer, null, 2)}</pre><h2>解析</h2><RichText>{question.analysisMarkdown}</RichText></section>}</>}</main></Shell>;
}

function AccountPage({ data }: { data: HubBootstrap }) { return <Shell data={data}><main className="hub-main narrow"><a href="/">← 返回主世界</a><h1>学习账号</h1><section className="hub-panel"><p>显示名称：{data.learner.displayName}</p><p>用户名：{data.learner.username}</p><p>学习身份 UUID：<code>{data.learner.id}</code></p><button onClick={async () => { await platformApi.logout(); go("/login"); }}>退出登录</button></section></main></Shell>; }

function AuthenticatedPlatform() {
  const path = window.location.pathname;
  const [data, setData] = useState<HubBootstrap>(); const [error, setError] = useState("");
  const load = async () => { try { setData(await platformApi.bootstrap()); } catch (reason) { if (reason instanceof HttpError && reason.status === 401) go("/login"); else setError((reason as Error).message); } };
  useEffect(() => { void load(); }, []);
  if (!data) return <main className="hub-loading">{error || "正在载入学习主世界…"}</main>;
  if (path === "/worlds/ancient-official") return <div className="world-shell"><a className="world-shell-home" href="/">← 主世界</a><App /></div>;
  if (path === "/study") return <StudyPage data={data} reload={load} />;
  if (path === "/books") return <BooksPage data={data} />;
  if (path.startsWith("/books/")) return <BookPage data={data} id={idAfter("/books/")} />;
  if (path.startsWith("/knowledge/")) return <KnowledgePage data={data} id={idAfter("/knowledge/")} />;
  if (path.startsWith("/questions/")) return <QuestionPage data={data} id={idAfter("/questions/")} />;
  if (path === "/account") return <AccountPage data={data} />;
  return <HubHome data={data} />;
}

export default function PlatformApp() {
  const path = window.location.pathname;
  if (path === "/login") return <AuthPage register={false} />;
  if (path === "/register") return <AuthPage register />;
  return <AuthenticatedPlatform />;
}

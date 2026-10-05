import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import App from "../App";
import { RichText } from "../components/RichText";
import { HttpError } from "../api/http";
import { platformApi, type BookDetail, type BrowseQuestion, type HubBootstrap, type KnowledgePoint, type KnowledgeState, type LearnerProgress, type PracticeSession, type ProgressChapter, type ReviewQueue, type ReviewQueueItem, type StudyProfile, type WrongQuestion } from "./api";
import { progressBandLabels, progressPercent } from "./progressView";
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
  const requestedNext = new URLSearchParams(window.location.search).get("next");
  const next = requestedNext?.startsWith("/") && !requestedNext.startsWith("//") ? requestedNext : "/";
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setBusy(true); setError("");
    try {
      if (register) await platformApi.register(username, displayName, password);
      else await platformApi.login(username, password);
      go(next);
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
      <nav><a href="/study">学习方向</a><a href="/progress">学习进度</a><a href="/reviews">复习安排</a><a href="/wrong-questions">错题练习</a><a href="/books">文集与知识图谱</a><a href="/#worlds">游戏世界</a>{data.canManage && <a href="/manage">管理后台</a>}<a href="/account">{data.learner.displayName}</a></nav>
    </header>
    {children}
  </div>;
}

function HubHome({ data }: { data: HubBootstrap }) {
  const selected = data.bankManifest.filter(book => data.studyProfile.selectedBookIds.includes(book.id));
  const [progress, setProgress] = useState<LearnerProgress | null>();
  useEffect(() => { platformApi.progress().then(setProgress).catch(() => setProgress(null)); }, []);
  return <Shell data={data}><main className="hub-main">
    <section className="hub-hero"><p className="eyebrow">Learning Hub · 学习主世界</p><h1>{data.learner.displayName}，今日从哪里继续？</h1>
      <p>文集决定完整学习范围，重点知识点记录当前关注方向。游戏世界从可玩知识点中随机确定目标，并共享同一份学习资源与学习身份。</p>
    </section>
    <div className="hub-grid">
      <section className="hub-panel"><div className="panel-heading"><div><small>FORMAL PRACTICE</small><h2>专项练习</h2></div><a href="/books">选择知识点</a></div>
        <p>选定一个知识点，完成每道正式题及其诊断或补救流程。没有固定题数和通关分数，进度直接体现在掌握度与记忆稳定度中。</p>
      </section>
      <section className="hub-panel"><div className="panel-heading"><div><small>WRONG PRACTICE</small><h2>错题练习</h2></div><a href="/wrong-questions">查看待重做</a></div>
        <p>同一道题最近一次正式结果为错误或部分正确时出现在这里；重新做对后自动移除。</p>
      </section>
      <section className="hub-panel"><div className="panel-heading"><div><small>MY STUDY</small><h2>我的学习</h2></div><a href="/study">调整学习方向</a></div>
        <p>当前模式：{data.studyProfile.focusMode === "manual" ? "手动重点" : "自动规划"} · {data.studyProfile.pace === "slow" ? "从容节奏" : "正常节奏"}</p>
        <div className="tag-row">{selected.map(book => <span key={book.id}>{book.name}</span>)}</div>
        <p>重点知识点：{data.studyProfile.focusedKnowledgePoints.map(point => point.name).join("、") || "由系统按可用知识范围自动安排"}</p>
      </section>
      <section className="hub-panel"><div className="panel-heading"><div><small>LIBRARY</small><h2>文集与知识图谱</h2></div><a href="/books">展开</a></div>
        <p>{data.bankManifest.length} 本可用文集，题目和知识点由服务器统一维护。浏览行为不会产生答题记录。</p>
      </section>
    </div>
    <section className="hub-panel progress-summary"><div className="panel-heading"><div><small>LEARNING PROGRESS</small><h2>当前学习进度</h2></div><a href="/progress">查看学习进度</a></div>
      {progress ? <div className="progress-summary-grid">
        <p><b>{progress.summary.totalKnowledgePoints}</b><span>当前学习范围</span></p>
        <p><b>{progress.summary.startedKnowledgePoints}</b><span>已开始</span></p>
        <p><b>{progress.summary.readyKnowledgePoints}</b><span>当前基本掌握</span></p>
        <p><b>{progress.summary.proficientKnowledgePoints}</b><span>熟练掌握</span></p>
        <p><b>{progress.summary.reviewDue}</b><span>当前待巩固</span></p>
      </div> : <p>{progress === null ? "学习进度暂时未能载入，可以稍后再看。" : "正在整理当前学习进度…"}</p>}
    </section>
    <section className="world-gallery" id="worlds"><div className="panel-heading"><div><small>WORLD GALLERY</small><h2>游戏世界</h2></div></div>
      <div className="world-cards">{data.worlds.map(world => <article key={world.id} className={world.enabled ? "world-card enabled" : "world-card"}>
        <p>{world.enabled ? "现已开放" : "筹备中"}</p><h3>{world.name}</h3><span>{world.description}</span>
        {world.enabled ? <a className="hub-primary" href={world.entryPath}>{world.initialized ? "继续旅程" : "初入此世"}</a> : <button disabled>尚未开放</button>}
      </article>)}</div>
    </section>
  </main></Shell>;
}

function ProgressChapterTree({ chapter, bookId }: { chapter: ProgressChapter; bookId: string }) {
  return <li><a href={`/books/${bookId}`}><span><code>{chapter.code}</code>{chapter.name}</span><small>{chapter.started} / {chapter.total} 已开始 · {chapter.ready} 基本掌握 · {chapter.proficient} 熟练掌握</small></a>
    {chapter.children.length > 0 && <ul>{chapter.children.map(child => <ProgressChapterTree chapter={child} bookId={bookId} key={child.chapterId} />)}</ul>}
  </li>;
}

function ProgressPage({ data }: { data: HubBootstrap }) {
  const [progress, setProgress] = useState<LearnerProgress>(); const [error, setError] = useState("");
  useEffect(() => { platformApi.progress().then(setProgress).catch(reason => setError((reason as Error).message)); }, []);
  const start = async (knowledgePointId: string) => { try { const session = await platformApi.startKnowledgePractice(knowledgePointId); go(`/practice/${session.id}`); } catch (reason) { setError((reason as Error).message); } };
  if (!progress) return <Shell data={data}><main className="hub-main"><a href="/">← 返回主世界</a><h1>学习进度</h1><p className={error ? "hub-error" : ""}>{error || "正在从当前学习范围整理进度…"}</p></main></Shell>;
  const total = progress.summary.totalKnowledgePoints;
  const maxDaily = Math.max(1, ...progress.recent.daily.map(day => day.gradedAttempts));
  const bandOrder = ["unstarted", "unmastered", "learning", "ready", "proficient"] as const;
  return <Shell data={data}><main className="hub-main progress-page"><a href="/">← 返回主世界</a><p className="eyebrow">LEARNING PROGRESS</p><h1>学习进度</h1>
    <p>进度按当前所选文集、有效掌握度、复习窗口和正式学习记录动态整理，会随学习与记忆变化。</p>{error && <p className="hub-error">{error}</p>}
    <section className="progress-hero-grid">
      <article><b>{total}</b><span>当前学习范围</span></article><article><b>{progress.summary.startedKnowledgePoints}</b><span>已开始</span></article>
      <article><b>{progress.summary.readyKnowledgePoints}</b><span>当前基本掌握</span></article><article><b>{progress.summary.proficientKnowledgePoints}</b><span>熟练掌握</span></article>
      <article><b>{progress.summary.reviewDue}</b><span>当前待巩固</span></article>
    </section>
    <section className="hub-panel progress-section"><div className="panel-heading"><div><small>MASTERY</small><h2>当前掌握分布</h2></div><span>当前基本掌握 {progress.summary.readyKnowledgePoints} / {total}</span></div>
      <div className="band-distribution">{bandOrder.map(band => <div key={band}><span className={`mastery-band ${band}`}>{progressBandLabels[band]}</span><div><i style={{width: `${progressPercent(progress.bands[band], total)}%`}} /></div><b>{progress.bands[band]}</b><small>{progressPercent(progress.bands[band], total)}%</small></div>)}</div>
      <div className="progress-review-windows">
        <p><b>{progress.summary.reviewDue}</b><span>当前待巩固</span></p>
        <p><b>{progress.summary.reviewSoon}</b><span>24 小时内建议巩固</span></p>
        <p><b>{progress.summary.reviewUpcoming}</b><span>未来 7 天建议巩固</span></p>
      </div>
    </section>
    <section className="progress-section"><div className="panel-heading"><div><small>SELECTED BOOKS</small><h2>按文集查看</h2></div><a href="/study">调整学习范围</a></div>
      {progress.books.length === 0 ? <div className="hub-panel"><h2>尚未选择学习文集</h2><p>先在学习方向中选择文集，再回来查看动态进度。</p></div> : <div className="progress-books">{progress.books.map(book => <article className="hub-panel" key={book.bookId}><div className="panel-heading"><div><h2>{book.name}</h2><p>{book.description}</p></div><a href={`/books/${book.bookId}`}>查看知识图谱</a></div>
        <div className="book-progress-metrics"><span>{book.totalKnowledgePoints}<small>知识点</small></span><span>{book.started}<small>已开始</small></span><span>{book.ready}<small>基本掌握</small></span><span>{book.proficient}<small>熟练掌握</small></span><span>{book.reviewDueOrSoon}<small>建议巩固</small></span></div>
        <ul className="chapter-progress-tree">{book.chapters.map(chapter => <ProgressChapterTree chapter={chapter} bookId={book.bookId} key={chapter.chapterId} />)}</ul>
      </article>)}</div>}
    </section>
    <section className="hub-panel progress-section"><div className="panel-heading"><div><small>RECENT 7 DAYS</small><h2>近期学习足迹</h2></div></div>
      <div className="recent-metrics"><p><b>{progress.recent.gradedAttempts7d}</b><span>正式作答</span></p><p><b>{progress.recent.distinctKnowledgePoints7d}</b><span>接触知识点</span></p><p><b>{progress.recent.activeStudyDays7d}</b><span>有正式学习的日期</span></p></div>
      <div className="activity-chart" aria-label="近七日正式作答数量">{progress.recent.daily.map(day => <div key={day.date} title={`${day.date}：${day.gradedAttempts} 次正式作答`}><span><i style={{height: `${Math.max(day.gradedAttempts ? 8 : 0, day.gradedAttempts / maxDaily * 100)}%`}} /></span><b>{day.gradedAttempts}</b><small>{new Date(`${day.date}T00:00:00Z`).toLocaleDateString("zh-CN", {month:"numeric",day:"numeric"})}</small></div>)}</div>
    </section>
    <section className="progress-section"><div className="panel-heading"><div><small>RECENT KNOWLEDGE</small><h2>最近学习</h2></div></div>
      {progress.recent.knowledgePoints.length === 0 ? <div className="hub-panel"><h2>还没有正式学习足迹</h2><p>从文集选择一个知识点开始专项练习，之后会在这里看到最近学习内容。</p></div> : <div className="recent-knowledge-grid">{progress.recent.knowledgePoints.map(point => <article className="hub-panel" key={point.knowledgePointId}><p className="eyebrow">{point.subject} · {point.section} · {point.chapter}</p><h2><a href={`/knowledge/${point.knowledgePointId}`}>{point.name}</a></h2><p><span className={`mastery-band ${point.band}`}>{progressBandLabels[point.band]}</span> · 有效掌握度 {Math.round(point.effectiveMastery)}%</p><p>记忆稳定度 {point.stabilityDays.toFixed(1)} 天 · 最近学习 {new Date(point.lastEvidenceAt).toLocaleString("zh-CN", {hour12:false})}</p><button className="hub-primary" onClick={() => start(point.knowledgePointId)}>继续专项练习</button></article>)}</div>}
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
      <p>重点知识点用于记录当前关注方向，不会缩小依赖检查和学习范围，也不改变游戏世界的随机目标选择。</p>
      {profile.focusMode === "manual" && <div className="knowledge-picker">{points.map(point => <label key={point.id}><input type="checkbox" checked={focus.includes(point.id)} onChange={() => setFocus(v => v.includes(point.id) ? v.filter(x => x !== point.id) : [...v, point.id])} /><span>{point.name}<small>{point.section} · {point.chapter}</small></span></label>)}</div>}
    </section>
    {message && <p className="hub-message">{message}</p>}<button className="hub-primary" onClick={save}>保存学习方向</button>
  </main></Shell>;
}

function BooksPage({ data }: { data: HubBootstrap }) { return <Shell data={data}><main className="hub-main narrow"><a href="/">← 返回主世界</a><h1>文集与知识图谱</h1><div className="book-list">{data.bankManifest.map(book => <a className="hub-panel" href={`/books/${book.id}`} key={book.id}><h2>{book.name}</h2><p>{book.description}</p><small>{book.knowledgePointCount} 个知识点 · {book.questionCount} 道已发布题目</small></a>)}</div></main></Shell>; }

const reviewGroups: { status: ReviewQueueItem["status"]; title: string; description: string }[] = [
  { status: "due", title: "现在适合巩固", description: "这些知识点已经进入合适的巩固窗口。" },
  { status: "soon", title: "24 小时内", description: "提前留意这些知识点，可以在记忆边界前自然回顾。" },
  { status: "upcoming", title: "未来 7 天", description: "接下来一周可能进入巩固窗口的知识点。" },
];

function ReviewCard({ item }: { item: ReviewQueueItem }) {
  const due = item.status === "due" ? "现在" : new Date(item.reviewDueAt).toLocaleString("zh-CN", { dateStyle: "medium", timeStyle: "short" });
  const start = async () => { const session = await platformApi.startKnowledgePractice(item.knowledgePointId); go(`/practice/${session.id}`); };
  return <article className="review-card">
    <p className="eyebrow">{item.subject} · {item.section}{item.chapter ? ` · ${item.chapter}` : ""}</p>
    <h3>{item.name}</h3>
    <div className="review-metrics"><span>有效掌握度 <b>{Math.round(item.effectiveMastery)}%</b></span><span>记忆稳定度 <b>{item.stabilityDays.toFixed(1)} 天</b></span><span>目标难度 <b>{item.targetDifficulty}</b></span><span>建议巩固 <b>{due}</b></span></div>
    <p className={item.playable ? "review-playable" : "review-waiting"}>{item.playable ? "当前可以正式巩固" : "暂待前置知识稳定后再安排"}</p>
    <button className="hub-primary" disabled={!item.playable} onClick={start}>现在巩固</button>
  </article>;
}

function ReviewsPage({ data }: { data: HubBootstrap }) {
  const [queue, setQueue] = useState<ReviewQueue>(); const [error, setError] = useState("");
  useEffect(() => { platformApi.reviewQueue().then(setQueue).catch(reason => setError((reason as Error).message)); }, []);
  return <Shell data={data}><main className="hub-main narrow"><a href="/">← 返回主世界</a><p className="eyebrow">REVIEW PLAN</p><h1>复习安排</h1>
    <p>复习时间由当前掌握度与记忆稳定度动态推导。需要巩固时，可以直接进入对应知识点专项练习。</p>
    {error && <p className="hub-error">{error}</p>}
    {!queue && !error && <p>正在整理复习安排…</p>}
    {queue && queue.items.length === 0 && <section className="hub-panel"><h2>当前安排从容</h2><p>未来 7 天暂时没有需要特别安排的知识点，继续按现有学习方向前进即可。</p></section>}
    {queue && reviewGroups.map(group => { const items = queue.items.filter(item => item.status === group.status); return <section className="review-group" key={group.status}><div><h2>{group.title}</h2><p>{group.description}</p></div>{items.length ? <div className="review-cards">{items.map(item => <ReviewCard item={item} key={item.knowledgePointId} />)}</div> : <p className="hub-panel">这一时段暂无安排。</p>}</section> })}
  </main></Shell>;
}

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
  const start = async () => { try { const session = await platformApi.startKnowledgePractice(id); go(`/practice/${session.id}`); } catch (reason) { setError((reason as Error).message); } };
  return <Shell data={data}><main className="hub-main narrow"><a href="/books">← 返回知识图谱</a>{error && <p className="hub-error">{error}</p>}{point && <><p className="eyebrow">{point.subject} · {point.section} · {point.chapter}</p><h1>{point.name}</h1><button className="hub-primary" onClick={start}>开始知识点专项练习</button>{state && <section className="hub-panel mastery-summary"><h2>当前状态</h2>{state.evidenceCount === 0 ? <p>尚未开始正式训练</p> : <><p className="mastery-score"><b>{bandLabel[state.band]}</b> · {Math.round(state.effectiveMastery)}%</p><p>记忆稳定度：{state.stabilityDays.toFixed(1)} 天</p><p>目标难度：{state.targetDifficulty}</p><p>最近练习：{state.lastEvidenceAt ? new Date(state.lastEvidenceAt).toLocaleDateString("zh-CN") : "—"}</p></>}</section>}<section className="hub-panel rich"><RichText>{point.explanation || point.description}</RichText></section><h2>相关已发布题目</h2><div className="question-list">{questions.map(question => <a href={`/questions/${question.id}`} key={question.id}><span>{question.sourceName || question.sourceType}</span><b><RichText>{question.contentMarkdown}</RichText></b></a>)}</div></>}</main></Shell>;
}

function WrongQuestionsPage({ data }: { data: HubBootstrap }) {
  const [items, setItems] = useState<WrongQuestion[]>([]); const [error, setError] = useState("");
  useEffect(() => { platformApi.wrongQuestions().then(setItems).catch(reason => setError((reason as Error).message)); }, []);
  const start = async (questionId: string) => { try { const session = await platformApi.startWrongPractice(questionId); go(`/practice/${session.id}`); } catch (reason) { setError((reason as Error).message); } };
  return <Shell data={data}><main className="hub-main narrow"><a href="/">← 返回主世界</a><p className="eyebrow">WRONG PRACTICE</p><h1>错题练习</h1>
    <p>这里只保留每道题最近一次正式作答仍需重做的项目，不展示错误次数或错误率。</p>{error && <p className="hub-error">{error}</p>}
    {items.length === 0 && <section className="hub-panel"><h2>当前没有待重做题目</h2><p>之后若同一道题出现错误或部分正确，会自动出现在这里。</p></section>}
    <div className="wrong-cards">{items.map(item => <article className="hub-panel" key={item.questionId}><p className="eyebrow">{item.subject} · {item.chapter}</p><h2>{item.knowledgePointName}</h2><p>{item.summary}</p><small>最近一次：{new Date(item.lastGradedAt).toLocaleString("zh-CN", { hour12: false })}</small><button className="hub-primary" onClick={() => start(item.questionId)}>重做这道题</button></article>)}</div>
  </main></Shell>;
}

function PracticePage({ data, id }: { data: HubBootstrap; id: string }) {
  const [session, setSession] = useState<PracticeSession>(); const [selected, setSelected] = useState<string[]>([]); const [error, setError] = useState("");
  const load = () => platformApi.practice(id).then(value => { setSession(value); setSelected([]); }).catch(reason => setError((reason as Error).message));
  useEffect(() => { void load(); }, [id]);
  if (!session) return <Shell data={data}><main className="hub-main narrow"><p>{error || "正在恢复专项练习…"}</p></main></Shell>;
  const attempt = session.currentAttempt; const question = attempt.question;
  const multiple = question.presentationType === "multiple_choice";
  const practiceOptions = Object.keys(question.options || {}).length
    ? question.options : question.presentationType === "true_false" ? { true: "正确", false: "错误" } : {};
  const toggle = (key: string) => setSelected(values => multiple ? (values.includes(key) ? values.filter(value => value !== key) : [...values, key]) : [key]);
  const submit = async () => { try {
    const answer = question.presentationType === "true_false" ? selected[0] === "true" : multiple ? selected : selected[0];
    setSession(await platformApi.answerPractice(session, answer));
  } catch (reason) { setError((reason as Error).message); } };
  const update = (action: Promise<PracticeSession>) => action.then(value => { setSession(value); setSelected([]); setError(""); }).catch(reason => setError((reason as Error).message));
  const finish = async () => { try { await platformApi.endPractice(id); go(session.intent === "wrong_review" ? "/wrong-questions" : `/knowledge/${session.targetKnowledgePointId}`); } catch (reason) { setError((reason as Error).message); } };
  return <Shell data={data}><main className="hub-main narrow practice-page"><button className="practice-exit" onClick={finish}>← 结束并返回</button><p className="eyebrow">{session.intent === "wrong_review" ? "错题重做" : "知识点专项"} · {attempt.targetKnowledgePointName}</p>
    <h1>{attempt.evidenceMode === "training" ? "补救训练" : "正式练习"}</h1>{error && <p className="hub-error">{error}</p>}
    <section className="hub-panel rich"><RichText>{question.question}</RichText><div className="practice-options">{Object.entries(practiceOptions).map(([key, text]) => <button className={selected.includes(key) ? "selected" : ""} disabled={attempt.status !== "active"} key={key} onClick={() => toggle(key)}><b>{key}.</b> {text}</button>)}</div></section>
    {attempt.status === "active" && question.gradingMode === "auto" && <button className="hub-primary" disabled={!selected.length} onClick={submit}>提交答案</button>}
    {attempt.status === "active" && question.gradingMode === "self_assessment" && <button className="hub-primary" onClick={() => update(platformApi.revealPractice(session))}>查看参考答案并自评</button>}
    {attempt.answerRevealed && <section className="hub-panel rich"><h2>参考答案</h2><pre>{JSON.stringify(attempt.standard, null, 2)}</pre>{attempt.explanation && <><h2>解析</h2><RichText>{attempt.explanation}</RichText></>}</section>}
    {attempt.status === "revealed" && <div className="practice-assessment"><button onClick={() => update(platformApi.assessPractice(session, "correct"))}>完全正确</button><button onClick={() => update(platformApi.assessPractice(session, "partial"))}>部分正确</button><button onClick={() => update(platformApi.assessPractice(session, "wrong"))}>需要重学</button></div>}
    {attempt.status === "graded" && <section className="hub-panel"><h2>{attempt.assessment === "correct" ? "本题已掌握" : attempt.assessment === "partial" ? "已有部分思路" : "错处已记录"}</h2><p>{session.flowComplete ? "当前知识点流程已经完成。" : "继续进入同一套诊断或补救流程。"}</p></section>}
    {attempt.status === "graded" && <div className="practice-actions">{session.flowComplete ? <>{session.canRepeat && <button className="hub-primary" onClick={() => update(platformApi.nextPractice(id))}>再来一道同知识点</button>}<button onClick={finish}>结束专项</button></> : <button className="hub-primary" onClick={() => update(platformApi.nextPractice(id))}>继续学习流程</button>}</div>}
  </main></Shell>;
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
  if (path === "/progress") return <ProgressPage data={data} />;
  if (path === "/reviews") return <ReviewsPage data={data} />;
  if (path === "/wrong-questions") return <WrongQuestionsPage data={data} />;
  if (path.startsWith("/practice/")) return <PracticePage data={data} id={idAfter("/practice/")} />;
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

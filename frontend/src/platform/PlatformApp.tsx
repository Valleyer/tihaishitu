import { useEffect, useState } from "react";
import type { CSSProperties, FormEvent } from "react";
import App from "../App";
import { RichText } from "../components/RichText";
import { Modal } from "../components/Modal";
import { HttpError } from "../api/http";
import { PAGE_SIZE } from "../pagination";
import { platformApi, type BookDetail, type BrowseQuestion, type HubBootstrap, type KnowledgeDirectoryItem, type KnowledgePoint, type KnowledgeState, type LearnerProgress, type LearnerStatistics, type PracticeSession, type ProgressChapter, type RecentChapter, type StudyProfile, type WrongQuestion } from "./api";
import { progressBandLabels } from "./progressView";
import { worldPresentation } from "./worldPresentation";
import { HubLink, navigate, useCurrentLocation } from "./navigation";
import { AnswerDisplay } from "./practiceView";
import { attemptKnowledgeTags, chapterProgressText, examTitle, practiceLabel, recentChapterMode } from "./practiceMeta";
import "./platform.css";

const go = navigate;
const idAfter = (prefix: string) => decodeURIComponent(window.location.pathname.slice(prefix.length));
const flattenChapters = (chapters: BookDetail["chapters"]): BookDetail["chapters"] => chapters;
/**
 * Chapter 当前可练知识点数：优先使用后端按学习者实时计算的 availableKnowledgePointCount；
 * 旧接口缺少该字段时回退到目录静态值 trainableKnowledgePointCount。缺字段时按 0 处理，
 * 避免 undefined 让按钮意外可点。
 */
const availableChapterPoints = (chapter: BookDetail["chapters"][number]) =>
  chapter.availableKnowledgePointCount ?? chapter.trainableKnowledgePointCount ?? 0;
/** 错题卡不可练习的原因文案；原因由后端 WrongQuestion.unavailableReason 判定，错题记录本身永久保留。 */
const wrongQuestionUnavailableLabel = (reason?: string | null) =>
  reason === "out_of_scope" ? "当前不在学习范围"
    : reason === "knowledge_unavailable" ? "该题所属知识点当前不可练习"
      : "该题当前不可练习";
const greeting = () => {
  const hour = new Date().getHours();
  if (hour < 6) return "夜深了";
  if (hour < 12) return "上午好";
  if (hour < 18) return "下午好";
  return "晚上好";
};
const recentTime = (value: string) => new Date(value).toLocaleString("zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit", hour12: false });
const questionTypeLabel: Record<string, string> = { single_choice: "单选题", multiple_choice: "多选题", true_false: "判断题", solution: "综合题" };
const sourceTypeLabel: Record<string, string> = { real_exam: "真题", mock: "模拟题", custom: "自建题" };
/** 只读题目标题取自来源/年份，不再使用 legacy subject_name 作为用户可见路径。 */
const questionTitle = (question: BrowseQuestion) =>
  [question.sourceName || sourceTypeLabel[question.sourceType] || "题目",
    question.examYear ? String(question.examYear) : "",
    question.displayQuestionNumber ? `${question.displayQuestionNumber} 题` : ""].filter(Boolean).join(" · ");
export const safePracticeReturnTo = (intent: PracticeSession["intent"]) => {
  const value = new URLSearchParams(window.location.search).get("returnTo");
  const allowed = value === "/study" || value === "/wrong-questions" || value === "/progress"
    || value?.startsWith("/progress/") || value?.startsWith("/knowledge/");
  return value && value.startsWith("/") && !value.startsWith("//") && !value.includes("://") && allowed
    ? value : intent === "wrong_review" || intent === "wrong_drill" ? "/wrong-questions" : "/study";
};
const practicePath = (id: string, returnTo: string) => `/practice/${id}?returnTo=${encodeURIComponent(returnTo)}`;

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
      await platformApi.bootstrap();
      go(next);
    } catch (reason) { setError((reason as Error).message); }
    finally { setBusy(false); }
  };
  return <main className="auth-page">
    <form className="auth-card" onSubmit={submit}>
      <HubLink className="auth-brand" href="/">万境求知</HubLink>
      <div><h1>{register ? "创建学习账号" : "欢迎回来"}</h1><p className="auth-subtitle">{register ? "建立属于你的统一学习身份" : "继续你的学习旅程"}</p></div>
      <label>用户名<input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" required /></label>
      {register && <label>显示名称<input value={displayName} onChange={e => setDisplayName(e.target.value)} /></label>}
      <label>密码<input type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete={register ? "new-password" : "current-password"} {...(register ? { minLength: 8 } : {})} required /></label>
      {error && <p className="hub-error" role="alert">{error}</p>}
      <button className="hub-primary" disabled={busy}>{busy ? "请稍候…" : register ? "注册并进入" : "登录"}</button>
      <HubLink href={register ? "/login" : "/register"}>{register ? "已有账号，直接登录" : "第一次来？注册学习账号"}</HubLink>
    </form>
  </main>;
}

function Shell({ data, children }: { data: HubBootstrap; children: React.ReactNode }) {
  const location = useCurrentLocation();
  const path = location.split(/[?#]/)[0];
  const nav = [
    ["首页", "/"], ["学习", "/study"], ["进度", "/progress"], ["统计", "/statistics"], ["题库", "/books"],
  ];
  const active = (href: string) => href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`);
  return <div className="learning-hub">
    <header className="hub-header"><div className="hub-header-inner"><HubLink className="hub-brand" href="/"><span aria-hidden="true" />万境求知</HubLink>
      <nav aria-label="主要导航">{nav.map(([label, href]) => <HubLink className={active(href) ? "active" : ""} href={href} key={href}>{label}</HubLink>)}</nav>
      <div className="hub-user">{data.canManage && <HubLink className="hub-manage-link" href="/manage">管理后台</HubLink>}<HubLink className={active("/account") ? "hub-account active" : "hub-account"} href="/account">{data.learner.displayName}</HubLink></div></div>
    </header>
    {children}
  </div>;
}

function HubHome({ data }: { data: HubBootstrap }) {
  const [progress, setProgress] = useState<LearnerProgress | null>();
  const [homeError, setHomeError] = useState("");
  useEffect(() => { platformApi.progress().then(setProgress).catch(() => { setProgress(null); setHomeError("学习数据暂时未能载入，可以稍后再试。"); }); }, []);
  return <Shell data={data}><main className="hub-main hub-home">
    <section className="hub-greeting"><h1>{greeting()}，{data.learner.displayName}</h1></section>
    {homeError && <p className="hub-error" role="alert">{homeError}</p>}
    <section className="world-gallery"><div className="world-cards">{data.worlds.map(world => { const presentation = worldPresentation(world.id); return <article key={world.id} className={world.enabled ? "world-card enabled" : "world-card"} style={{ "--world-accent": presentation.accent } as CSSProperties}>
        <div className={presentation.cover ? "world-cover" : "world-cover fallback"}><span className="world-cover-media" style={presentation.cover ? { backgroundImage: `url(${presentation.cover})` } : undefined} /><span className={world.enabled ? "world-card-status open" : "world-card-status"}>{world.enabled ? "已开放" : "即将开放"}</span></div>
        <div className="world-card-body"><h3>{world.name}</h3><p>{world.description}</p>{presentation.tags.length > 0 && <div className="world-tags">{presentation.tags.map(tag => <span key={tag}>{tag}</span>)}</div>}{world.enabled ? <HubLink className="world-entry" href={world.entryPath}>{world.initialized ? "继续旅程" : "初入此世"} →</HubLink> : <span className="world-unavailable">尚未开放</span>}</div>
      </article> })}</div>
    </section>
    <div className="home-lower-grid"><section className="recent-home"><div className="home-module-action"><HubLink href="/progress">全部记录 →</HubLink></div>{progress?.recent.knowledgePoints.length ? <div className="recent-home-list">{progress.recent.knowledgePoints.slice(0, 5).map(point => <HubLink href={`/knowledge/${point.knowledgePointId}`} key={point.knowledgePointId}><div><b>{point.name}</b><span>{point.bookName} · {point.chapterName}</span></div><div><span className={`mastery-band ${point.band}`}>{progressBandLabels[point.band]}</span><small>{Math.round(point.effectiveMastery)}% · {recentTime(point.lastEvidenceAt)}</small></div></HubLink>)}</div> : <div className="empty-state"><h3>还没有学习记录</h3><p>从一个知识点开始，学习记录会出现在这里。</p></div>}</section>
      <section className="quick-links"><div><HubLink href="/study"><b>章节知识练习</b><span>按文集和章节系统推进</span></HubLink><HubLink href="/wrong-questions"><b>错题本</b><span>长期保留并反复训练历史错题</span></HubLink><HubLink href="/books"><b>浏览题库</b><span>按知识点查看已发布题目</span></HubLink><HubLink href="/statistics"><b>学习统计</b><span>查看近期正式学习足迹</span></HubLink></div></section></div>
  </main></Shell>;
}

function ProgressChapterTree({ chapter, bookId }: { chapter: ProgressChapter; bookId: string }) {
  return <li><HubLink href={`/progress/books/${bookId}/chapters/${chapter.chapterId}`}><span>{chapter.name}</span><small>掌握进度 {Math.round(chapter.masteryProgress)}% · {chapter.total} 个知识点</small></HubLink>
  </li>;
}

function ProgressPage({ data }: { data: HubBootstrap }) {
  const [progress, setProgress] = useState<LearnerProgress>(); const [error, setError] = useState("");
  useEffect(() => { platformApi.progress().then(setProgress).catch(reason => setError((reason as Error).message)); }, []);
  return <Shell data={data}><main className="hub-main progress-page">
    {error && <p className="hub-error">{error}</p>}{!progress && !error && <p>正在整理学习进度…</p>}
    {progress&&<section className="overview-card"><div className="section-heading"><h2>当前学习范围</h2></div><div className="overview-metrics"><p><b>{progress.summary.totalKnowledgePoints}</b><span>知识点</span></p><p><b>{progress.summary.startedKnowledgePoints}</b><span>已开始</span></p><p><b>{progress.summary.readyKnowledgePoints}</b><span>熟练掌握及以上</span></p><p><b>{progress.summary.proficientKnowledgePoints}</b><span>彻底掌握</span></p></div></section>}
    <div className="progress-books">{progress?.books.map(book => <article className="hub-panel progress-book-card" key={book.bookId}><h2>{book.name}</h2><div className="mastery-progress"><span style={{width:`${book.masteryProgress}%`}} /></div><b>{Math.round(book.masteryProgress)}%</b><small>掌握进度 · {book.totalKnowledgePoints} 个知识点 · 已开始 {book.started} · 熟练掌握及以上 {book.ready} · 彻底掌握 {book.proficient}</small><HubLink href={`/progress/books/${book.bookId}`}>查看章节进度 →</HubLink></article>)}</div>
    {progress?.books.length === 0 && <section className="hub-panel"><h2>尚未选择学习文集</h2><p>先到学习页设置学习范围。</p><HubLink className="hub-primary" href="/study">设置学习范围</HubLink></section>}
  </main></Shell>;
}

function ProgressBookPage({ data, bookId }: { data: HubBootstrap; bookId: string }) {
  const [progress, setProgress] = useState<LearnerProgress>(); const [error, setError] = useState("");
  useEffect(() => { platformApi.progress().then(setProgress).catch(reason => setError((reason as Error).message)); }, [bookId]);
  const book = progress?.books.find(item => item.bookId === bookId);
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/progress">← 返回文集进度</HubLink>{error && <p className="hub-error">{error}</p>}{book && <><p className="eyebrow">文集进度</p><h1>{book.name}</h1><div className="mastery-progress"><span style={{width:`${book.masteryProgress}%`}} /></div><p>掌握进度 {Math.round(book.masteryProgress)}% · {book.totalKnowledgePoints} 个知识点</p><ul className="chapter-progress-tree">{book.chapters.map(chapter => <ProgressChapterTree chapter={chapter} bookId={bookId} key={chapter.chapterId} />)}</ul></>}</main></Shell>;
}

function findProgressChapter(chapters: ProgressChapter[], id: string): ProgressChapter | undefined { return chapters.find(chapter => chapter.chapterId === id); }
function findBookChapter(chapters: BookDetail["chapters"], id: string): BookDetail["chapters"][number] | undefined { return chapters.find(chapter => chapter.id === id); }
function chapterPoints(chapter: BookDetail["chapters"][number]): KnowledgePoint[] { return chapter.knowledgePoints; }

function ProgressChapterPage({ data, bookId, chapterId }: { data: HubBootstrap; bookId: string; chapterId: string }) {
  const [progress, setProgress] = useState<LearnerProgress>(); const [book, setBook] = useState<BookDetail>(); const [states, setStates] = useState(new Map<string, KnowledgeState>()); const [error, setError] = useState("");
  useEffect(() => { Promise.all([platformApi.progress(), platformApi.book(bookId), platformApi.knowledgeStatesForBook(bookId)]).then(([p,b,s]) => { setProgress(p); setBook(b); setStates(new Map(s.map(item => [item.knowledgePointId,item]))); }).catch(reason => setError((reason as Error).message)); }, [bookId, chapterId]);
  const aggregate = progress?.books.find(item => item.bookId === bookId); const chapterProgress = aggregate && findProgressChapter(aggregate.chapters, chapterId); const chapter = book && findBookChapter(book.chapters, chapterId);
  const start = async (id: string) => { try { const session=await platformApi.startKnowledgePractice(id); go(practicePath(session.id, `/progress/books/${bookId}/chapters/${chapterId}`)); } catch(reason){ setError((reason as Error).message); } };
  return <Shell data={data}><main className="hub-main narrow"><HubLink href={`/progress/books/${bookId}`}>← 返回章节进度</HubLink>{error && <p className="hub-error">{error}</p>}{chapter && chapterProgress && <><p className="eyebrow">章节进度</p><h1>{chapter.name}</h1><div className="mastery-progress"><span style={{width:`${chapterProgress.masteryProgress}%`}} /></div><p>掌握进度 {Math.round(chapterProgress.masteryProgress)}% · {chapterProgress.total} 个知识点</p><div className="progress-point-list">{chapterPoints(chapter).map(point => { const state=states.get(point.id); return <article className="hub-panel" key={point.id}><div><h2><HubLink href={`/knowledge/${point.id}`}>{point.name}</HubLink></h2><p>{state?.lastEvidenceAt ? `最近学习 ${new Date(state.lastEvidenceAt).toLocaleDateString("zh-CN")}` : "未开始"}</p></div><div><span className={`mastery-band ${state?.band || "unstarted"}`}>{progressBandLabels[state?.band || "unstarted"]}</span><b>{Math.round(state?.effectiveMastery || 0)}%</b><button onClick={() => start(point.id)}>开始专项</button></div></article> })}</div></>}</main></Shell>;
}

export function StudyPage({ data, reload }: { data: HubBootstrap; reload: () => Promise<void> }) {
  const [profile, setProfile] = useState<StudyProfile>(data.studyProfile); const [selected, setSelected] = useState(data.studyProfile.selectedBookIds);
  const [details, setDetails] = useState<BookDetail[]>([]); const [wrongCount, setWrongCount] = useState(0);
  const [progress,setProgress]=useState<LearnerProgress|null>();
  const [recent,setRecent]=useState<RecentChapter|null>();
  const [bookId, setBookId] = useState(""); const [chapterId, setChapterId] = useState(""); const [message, setMessage] = useState("");
  useEffect(() => { Promise.all(data.studyProfile.selectedBookIds.map(id => platformApi.book(id))).then(setDetails).catch(e => setMessage(e.message)); platformApi.wrongQuestions().then(items => setWrongCount(items.length)); platformApi.progress().then(setProgress).catch(()=>setProgress(null)); platformApi.recentChapter().then(setRecent).catch(()=>setRecent(null)); }, [data.studyProfile.selectedBookIds]);
  const book=details.find(item=>item.id===bookId); const chapters=book ? flattenChapters(book.chapters) : []; const chapter=chapters.find(item=>item.id===chapterId);
  const scopedBooks=data.bankManifest.filter(item=>profile.selectedBookIds.includes(item.id));
  const start=async()=>{if(!book||!chapter)return;try{const session=await platformApi.startChapterPractice(book.id,chapter.id);go(practicePath(session.id,"/study"))}catch(reason){setMessage((reason as Error).message)}};
  const save=async()=>{try{const updated=await platformApi.updateProfile(profile,selected,profile.focusedKnowledgePointIds);setProfile(updated);setSelected(updated.selectedBookIds);if(bookId&&!updated.selectedBookIds.includes(bookId)){setBookId("");setChapterId("")}setMessage("学习范围已保存");await reload()}catch(reason){setMessage((reason as Error).message)}};
  /** 再次练习：用最近一次章节练习的 Book + Chapter 新建 chapter_drill，不恢复已结束的 Session。 */
  const again=async()=>{if(!recent?.bookId||!recent?.chapterId)return;try{const session=await platformApi.startChapterPractice(recent.bookId,recent.chapterId);go(practicePath(session.id,"/study"))}catch(reason){setMessage((reason as Error).message)}};
  /** 快速练习错题：随机连续刷 active 错题，答对不会自动移出错题本。 */
  const quickWrong=async()=>{try{const session=await platformApi.startWrongDrill();go(practicePath(session.id,"/study"))}catch(reason){setMessage((reason as Error).message)}};
  const mode=recentChapterMode(recent);
  return <Shell data={data}><main className="hub-main study-page">
    <section className="learning-focus-grid">
      <article className="continue-card">
        {mode==="active"&&recent?<><h2>{recent.bookName||"章节练习"}</h2><div className="continue-mastery"><span>{recent.chapterName||"章节练习"}</span><span>{chapterProgressText(recent)}</span></div><button className="hub-primary" onClick={()=>go(practicePath(recent.activeSessionId!,"/study"))}>继续章节练习 →</button></>:
         mode==="last"&&recent?<><p className="section-kicker">最近练习章节</p><h2>{recent.bookName||"章节练习"}</h2><div className="continue-meta"><span>{recent.chapterName||"章节练习"}</span><small className="continue-time">{recent.updatedAt?`最近练习 ${recentTime(recent.updatedAt)}`:"最近练习时间未知"}</small></div><button className="hub-primary" onClick={again}>再次练习 →</button></>:
         <><h2>开始章节学习</h2><p>从下方选择文集与章节。</p></>}
      </article>
      <article className="today-card"><div className="section-heading"><h2>学习状态</h2></div>{progress?<div className="today-metrics"><p><b>{progress.summary.startedKnowledgePoints}</b><span>已开始知识点</span></p><p><b>{progress.summary.readyKnowledgePoints}</b><span>熟练掌握及以上</span></p><p><b>{progress.summary.wrongQuestions}</b><span>错题本题目</span></p><p><b>{progress.recent.gradedAttempts7d}</b><span>近 7 日正式作答</span></p></div>:<p className="muted">{progress===null?"暂时无法读取学习状态":"正在整理学习状态…"}</p>}</article></section>
    <section className="hub-panel study-drill" id="chapter-drill"><div className="panel-heading"><h2>章节知识练习</h2></div>
      <div className="study-book-grid">{scopedBooks.map(item=>{const trainable=item.knowledgePointCount>0;return <button type="button" disabled={!trainable} className={bookId===item.id?"book-cover-card selected":"book-cover-card"} key={item.id} onClick={()=>{setBookId(item.id);setChapterId("")}}><span className="book-cover-icon"><svg aria-hidden="true" viewBox="0 0 24 24"><path d="M5 4.5A2.5 2.5 0 0 1 7.5 2H20v16H7.5A2.5 2.5 0 0 0 5 20.5v-16Z"/><path d="M5 20.5A2.5 2.5 0 0 1 7.5 18H20v4H7.5A2.5 2.5 0 0 1 5 19.5V4"/><path d="M9 6h7"/></svg></span><b>{item.name}</b><small>{item.knowledgePointCount} 个知识点</small>{!trainable?<em>暂无已发布正式题</em>:null}</button>})}</div>
      {book && <><h3>选择章节</h3><div className="study-chapters">{chapters.map(item=><button className={chapterId===item.id?"selected":""} onClick={()=>setChapterId(item.id)} key={item.id}>{item.name}</button>)}</div></>}
      {chapter && <div className="chapter-drill-action"><p>本章共 {chapter.knowledgePointCount} 个知识点，当前 {availableChapterPoints(chapter)} 个知识点可练。</p><button className="hub-primary" disabled={availableChapterPoints(chapter)===0} onClick={start}>{availableChapterPoints(chapter)>0?"开始章节练习":"暂无可练正式题"}</button></div>}
      {scopedBooks.length===0 && <p className="empty-state">尚未选择学习范围</p>}
    </section>
      <section className="hub-panel wrong-entry"><div><h2>错题本</h2><p>已保留 {wrongCount} 道错题</p></div><div className="wrong-entry-actions"><button className="hub-primary" disabled={wrongCount===0} onClick={quickWrong}>快速练习错题</button><HubLink href="/wrong-questions">进入错题本</HubLink></div></section>
    <details className="hub-panel study-settings"><summary>学习范围设置</summary><div className="choice-list">{data.bankManifest.map(item=><label key={item.id}><input type="checkbox" checked={selected.includes(item.id)} onChange={()=>setSelected(v=>v.includes(item.id)?v.filter(id=>id!==item.id):[...v,item.id])}/><span><b>{item.name}</b><small>{item.knowledgePointCount} 个知识点</small></span></label>)}</div><button className="hub-primary" onClick={save}>保存学习范围</button></details>
    {message&&<p className="hub-message">{message}</p>}
  </main></Shell>;
}

function BooksPage({ data }: { data: HubBootstrap }) {
  const [items,setItems]=useState<KnowledgeDirectoryItem[]>([]); const [query,setQuery]=useState(""); const [bookId,setBookId]=useState(""); const [chapterId,setChapterId]=useState(""); const [book,setBook]=useState<BookDetail>(); const [error,setError]=useState(""); const [page,setPage]=useState(0); const [totalElements,setTotalElements]=useState(0); const [totalPages,setTotalPages]=useState(0);
  const load=()=>platformApi.knowledgeDirectory({query,bookId,chapterId,page,size:PAGE_SIZE}).then(result=>{setItems(result.content);setTotalElements(result.totalElements);setTotalPages(result.totalPages)}).catch(reason=>setError((reason as Error).message));
  useEffect(()=>{void load()},[query,bookId,chapterId,page]); useEffect(()=>{if(bookId)platformApi.book(bookId).then(setBook).catch(reason=>setError((reason as Error).message));else setBook(undefined)},[bookId]);
  const selectedBooks=data.bankManifest.filter(item=>data.studyProfile.selectedBookIds.includes(item.id)); const chapters=book?flattenChapters(book.chapters):[];
  const reset=()=>setPage(0);
  return <Shell data={data}><main className="hub-main question-bank-page">{error&&<p className="hub-error">{error}</p>}
    <div className="bank-filters"><input value={query} onChange={e=>{setQuery(e.target.value);reset()}} placeholder="搜索知识点名称"/><select value={bookId} onChange={e=>{setBookId(e.target.value);setChapterId("");reset()}}><option value="">全部文集</option>{selectedBooks.map(item=><option value={item.id} key={item.id}>{item.name}</option>)}</select><select value={chapterId} onChange={e=>{setChapterId(e.target.value);reset()}} disabled={!bookId}><option value="">全部章节</option>{chapters.map(item=><option value={item.id} key={item.id}>{item.name}</option>)}</select></div>
    <div className="knowledge-directory">{items.map(item=><HubLink className="hub-panel" href={`/knowledge/${item.id}?bookId=${encodeURIComponent(item.bookId)}&chapterId=${encodeURIComponent(item.chapterId)}`} key={item.id}><div><h2>{item.name}</h2></div><p>{item.bookName} · {item.catalogChapter}</p><b>{item.publishedQuestionCount}</b><small>道已发布题目</small></HubLink>)}</div>{items.length===0&&<p className="empty-state">没有符合条件的知识点。</p>}
    <div className="directory-pagination"><span>共 {totalElements} 个知识点</span><span>第 {totalPages?page+1:0} / {totalPages} 页</span><button disabled={page===0} onClick={()=>setPage(value=>value-1)}>上一页</button><button disabled={!totalPages||page>=totalPages-1} onClick={()=>setPage(value=>value+1)}>下一页</button></div>
  </main></Shell>;
}

function ActivityBarChart({ title, days, values, unit }: { title: string; days: number; values: { date: string; value: number }[]; unit: string }) {
  const max = Math.max(1, ...values.map(item => item.value));
  const ceiling = Math.max(1, Math.ceil(max / 5) * 5);
  const ticks = [ceiling, Math.round(ceiling / 2), 0];
  const every = days === 7 ? 1 : days === 30 ? 5 : 14;
  const empty = values.every(item => item.value === 0);
  return <section className="hub-panel statistics-activity-chart"><div className="activity-chart-heading"><h2>{title}</h2><span>近 {days} 天 · 单位：{unit}</span></div><div className="activity-chart-body">
    <div className="chart-y-title">数量（{unit}）</div><div className="chart-y-axis">{ticks.map(tick=><span key={tick}>{tick}</span>)}</div>
    <div className="chart-plot">{empty&&<strong className="chart-empty">暂无学习记录</strong>}<div className="chart-bars">{values.map((item,index)=><div className="chart-column" title={`${item.date}：${item.value} ${unit}`} key={item.date}><i style={{height:`${item.value/ceiling*100}%`}}/><span>{index%every===0||index===values.length-1?item.date.slice(5):""}</span></div>)}</div><div className="chart-x-title">日期</div></div>
  </div></section>;
}

function StatisticsPage({ data }: { data: HubBootstrap }) {
  const [days,setDays]=useState<7|30|90>(7); const [stats,setStats]=useState<LearnerStatistics>(); const [error,setError]=useState("");
  useEffect(()=>{setStats(undefined);platformApi.statistics(days).then(setStats).catch(reason=>setError((reason as Error).message))},[days]);
  const outcomeTotal=(stats?.summary.correct||0)+(stats?.summary.partial||0)+(stats?.summary.wrong||0)||1;
  return <Shell data={data}><main className="hub-main statistics-page"><div className="panel-heading"><h1>学习统计</h1><div className="segmented">{([7,30,90] as const).map(value=><button className={days===value?"active":""} onClick={()=>setDays(value)} key={value}>近 {value} 天</button>)}</div></div>{error&&<p className="hub-error">{error}</p>}{!stats&&!error&&<p>正在整理统计…</p>}{stats&&<>
    <section className="statistics-summary"><article><b>{stats.summary.gradedAttempts}</b><span>正式作答</span></article><article><b>{stats.summary.activeStudyDays}</b><span>活跃学习日</span></article><article><b>{stats.summary.distinctKnowledgePoints}</b><span>接触知识点</span></article><article><b>{stats.summary.knowledgeDrillAttempts}</b><span>专项作答</span></article><article><b>{stats.summary.wrongReviewAttempts}</b><span>错题作答</span></article><article><b>{stats.summary.worldAttempts}</b><span>世界作答</span></article></section>
    <ActivityBarChart title="每日正式作答" days={days} unit="题" values={stats.daily.map(day=>({date:day.date,value:day.gradedAttempts}))}/>
    <ActivityBarChart title="每日接触知识点" days={days} unit="个" values={stats.daily.map(day=>({date:day.date,value:day.distinctKnowledgePoints}))}/>
    <section className="statistics-grid"><article className="hub-panel"><h2>正式作答结果分布</h2>{[["正确",stats.summary.correct],["部分正确",stats.summary.partial],["需巩固",stats.summary.wrong]].map(([label,value])=><div className="horizontal-stat" key={String(label)}><span>{label}</span><i><b style={{width:`${Number(value)/outcomeTotal*100}%`}}/></i><strong>{value}</strong></div>)}</article><article className="hub-panel"><h2>文集掌握进度</h2>{stats.books.map(book=><div className="horizontal-stat" key={book.bookId}><span>{book.name}</span><i><b style={{width:`${book.masteryProgress}%`}}/></i><strong>{Math.round(book.masteryProgress)}%</strong></div>)}</article></section>
  </>}</main></Shell>;
}

const bandLabel: Record<KnowledgeState["band"], string> = progressBandLabels;

function ChapterSection({ chapter, bookId, states }: { chapter: BookDetail["chapters"][number]; bookId: string; states: Map<string, KnowledgeState> }) {
  return <section className="hub-panel"><h2>{chapter.name}</h2><p>{chapter.description}</p><div className="knowledge-links">{chapter.knowledgePoints.map(point => { const state = states.get(point.id); return <HubLink href={`/knowledge/${point.id}?bookId=${encodeURIComponent(bookId)}&chapterId=${encodeURIComponent(chapter.id)}`} key={point.id}>{point.name}<small>{point.description}</small><span className={`mastery-band ${state?.band || "unstarted"}`}>{bandLabel[state?.band || "unstarted"]}{state?.evidenceCount ? ` · ${state.effectiveMastery.toFixed(1)}%` : ""}</span></HubLink> })}</div></section>;
}

function BookPage({ data, id }: { data: HubBootstrap; id: string }) {
  const [book, setBook] = useState<BookDetail>(); const [states, setStates] = useState(new Map<string, KnowledgeState>()); const [error, setError] = useState("");
  useEffect(() => { Promise.all([platformApi.book(id), platformApi.knowledgeStatesForBook(id)]).then(([value, stateList]) => { setBook(value); setStates(new Map(stateList.map(state => [state.knowledgePointId, state]))) }).catch(e => setError(e.message)); }, [id]);
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/books">← 返回题库</HubLink>{error && <p className="hub-error">{error}</p>}{book && <><h1>{book.name}</h1><p>{book.description}</p><p>{book.knowledgePointCount} 个知识点 · {book.questionCount} 道已发布题目</p>{book.chapters.map(chapter => <ChapterSection chapter={chapter} bookId={book.id} states={states} key={chapter.id} />)}</>}</main></Shell>;
}

function ReadonlyQuestion({ question }: { question: BrowseQuestion }) { return <><div className="question-meta"><span>{question.examYear||""}{question.displayQuestionNumber?` · 第 ${question.displayQuestionNumber} 题`:""}</span><span>{questionTypeLabel[question.questionType] || "题目"}</span><span>难度 {question.difficulty}</span></div><section className="hub-panel rich"><RichText>{question.contentMarkdown}</RichText>{question.options?.map(option=><p className="readonly-option" key={option.key}><b>{option.key}.</b> <RichText inline>{option.text}</RichText></p>)}</section><div className="tag-row">{question.knowledgePoints?.map(point=><HubLink className={`knowledge-tag ${point.role||"core"}`} href={`/knowledge/${point.id}`} key={point.id}>{point.name}</HubLink>)}</div></>; }

export function QuestionPreviewCard({ summary }: { summary: BrowseQuestion }) { const [open,setOpen]=useState(false); const [question,setQuestion]=useState<BrowseQuestion>(); const toggle=async()=>{if(!open&&!question)setQuestion(await platformApi.question(summary.id));setOpen(v=>!v)}; return <article className="hub-panel question-summary"><div><p>{summary.sourceName||sourceTypeLabel[summary.sourceType]||"题目"}{summary.examYear?` · ${summary.examYear}`:""}{summary.displayQuestionNumber?` · 第${summary.displayQuestionNumber}题`:""}{summary.learnerQuestionStatus==="mastered"&&<span className="question-mastered">✓ 已掌握</span>}</p><h3><RichText>{summary.contentMarkdown}</RichText></h3><div className="tag-row">{summary.knowledgePoints?.map(point=><span className={`knowledge-tag ${point.role||"core"}`} key={point.id}>{point.name}</span>)}</div></div><div className="question-summary-actions"><button onClick={toggle}>{open?"收起预览":"预览"}</button><HubLink href={`/questions/${summary.id}`}>查看答案与解析</HubLink></div>{open&&question&&<div className="inline-question-preview"><ReadonlyQuestion question={question}/></div>}</article>; }

function KnowledgePage({ data, id }: { data: HubBootstrap; id: string }) {
  const [point,setPoint]=useState<(KnowledgePoint&{books:{id:string;name:string;chapterId:string;chapterName:string}[]})>(); const [questions,setQuestions]=useState<BrowseQuestion[]>([]); const [state,setState]=useState<KnowledgeState>(); const [neighbors,setNeighbors]=useState<Awaited<ReturnType<typeof platformApi.knowledgeNeighbors>>>(); const [guide,setGuide]=useState<Awaited<ReturnType<typeof platformApi.knowledgeGuide>>>(); const [guideOpen,setGuideOpen]=useState(false); const [error,setError]=useState("");
  useEffect(()=>{Promise.all([platformApi.knowledge(id),platformApi.knowledgeQuestions(id),platformApi.knowledgeState(id)]).then(([p,q,s])=>{setPoint(p);setQuestions(q);setState(s)}).catch(e=>setError(e.message))},[id]);
  const requestedBook=new URLSearchParams(window.location.search).get("bookId"); const requestedChapter=new URLSearchParams(window.location.search).get("chapterId");
  const context=point?.books.find(item=>item.id===requestedBook&&item.chapterId===requestedChapter)||point?.books[0];
  useEffect(()=>{if(context)platformApi.knowledgeNeighbors(id,context.id,context.chapterId).then(setNeighbors).catch(()=>setNeighbors(undefined))},[id,context?.id,context?.chapterId]);
  const contextQuery=context?`?bookId=${encodeURIComponent(context.id)}&chapterId=${encodeURIComponent(context.chapterId)}`:"";
  const start=async()=>{try{const session=await platformApi.startKnowledgePractice(id);go(practicePath(session.id,`/knowledge/${id}${contextQuery}`))}catch(reason){setError((reason as Error).message)}};
  const openGuide=async()=>{setGuideOpen(true);try{setGuide(await platformApi.knowledgeGuide(id))}catch(reason){setError((reason as Error).message)}};
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/books">← 返回题库</HubLink>{error&&<p className="hub-error">{error}</p>}{point&&<><p className="eyebrow">{context?`${context.name} · ${context.chapterName}`:"知识点"}</p><h1>{point.name}</h1><div className="knowledge-actions"><button className="hub-primary" onClick={start}>开始知识点练习</button><button onClick={openGuide}>知识讲解</button>{neighbors?.next?<HubLink href={`/knowledge/${neighbors.next.id}?bookId=${encodeURIComponent(context!.id)}&chapterId=${encodeURIComponent(neighbors.next.chapterId)}`}>下一个知识点 →</HubLink>:<span className="muted">已到文集末尾</span>}</div>{state&&<section className={`hub-panel mastery-summary ${state.band==="proficient"?"mastery-perfect":""}`}><h2>当前状态</h2><p className="mastery-score"><b>{state.band==="proficient"?"✦ ":""}{bandLabel[state.band]}</b> · {state.effectiveMastery.toFixed(1)}%</p><p>{state.evidenceCount?`最近练习：${state.lastEvidenceAt?new Date(state.lastEvidenceAt).toLocaleDateString("zh-CN"):"—"}`:"尚未开始正式训练"}</p></section>}<h2>相关正式真题</h2><div className="question-preview-list">{questions.map(question=><QuestionPreviewCard summary={question} key={question.id}/>)}</div>{questions.length===0&&<p className="empty-state">当前没有已发布题目。</p>}{guideOpen&&<Modal title={`${point.name} · 知识讲解`} wide close={()=>setGuideOpen(false)}>{guide===undefined?<p>正在载入知识讲解…</p>:guide.contentMarkdown?<div className="rich"><RichText>{guide.contentMarkdown}</RichText></div>:<div className="empty-state"><h3>知识讲解尚未录入</h3></div>}</Modal>}</>}</main></Shell>;
}

export function WrongQuestionsPage({ data }: { data: HubBootstrap }) {
  const [items, setItems] = useState<WrongQuestion[]>([]); const [error, setError] = useState("");
  const load=()=>platformApi.wrongQuestions().then(setItems).catch(reason => setError((reason as Error).message));
  useEffect(() => { void load(); }, []);
  const start = async (questionId: string) => { try { const session = await platformApi.startWrongPractice(questionId); go(practicePath(session.id,"/wrong-questions")); } catch (reason) { setError((reason as Error).message); } };
  const remove = async (questionId: string) => {
    if (!window.confirm("确认已经掌握这道题并将它移出错题本吗？\n如果以后再次做错，它会自动重新加入。")) return;
    try { await platformApi.removeWrongQuestion(questionId); await load(); } catch (reason) { setError((reason as Error).message); }
  };
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/study">← 返回学习</HubLink><h1>错题本</h1>
    {error && <p className="hub-error">{error}</p>}
    {items.length === 0 && <section className="hub-panel"><h2>错题本还是空的</h2></section>}
    <div className="wrong-cards">{items.map(item => <article className="hub-panel" key={item.questionId}>{item.examLabel&&<div className="practice-exam-meta"><span className="practice-exam-label">{item.examLabel}</span>{item.displayQuestionNumber&&<span className="practice-question-number">第{item.displayQuestionNumber}题</span>}</div>}<h2>{item.knowledgePointName}</h2>{item.knowledgePoints&&item.knowledgePoints.length>0&&<div className="tag-row">{item.knowledgePoints.map(point=><HubLink className={`knowledge-tag ${point.role||"core"}`} href={`/knowledge/${point.id}`} key={point.id}>{point.name}</HubLink>)}</div>}<div className="wrong-question-content"><RichText>{item.contentMarkdown}</RichText></div><small>最近做错：{new Date(item.lastGradedAt).toLocaleString("zh-CN", { hour12: false })}</small>{!item.available&&<p className="muted">{wrongQuestionUnavailableLabel(item.unavailableReason)}</p>}<div className="wrong-actions"><button className="hub-primary" disabled={!item.available} onClick={() => start(item.questionId)}>重做这道题</button><button className="secondary" onClick={() => remove(item.questionId)}>移出错题本</button></div></article>)}</div>
  </main></Shell>;
}

export function PracticePage({ data, id }: { data: HubBootstrap; id: string }) {
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
  const finish = async () => { try { await platformApi.endPractice(id); go(safePracticeReturnTo(session.intent)); } catch (reason) { setError((reason as Error).message); } };
  const answerDetails = attempt.answerRevealed && <section className="hub-panel rich practice-answer">{question.gradingMode === "self_assessment" ? <><h2>参考解析</h2><RichText>{attempt.explanation || ""}</RichText></> : <><h2>参考答案</h2><AnswerDisplay standard={attempt.standard} presentationType={question.presentationType} options={practiceOptions}/>{attempt.explanation && <><h2>解析</h2><RichText>{attempt.explanation}</RichText></>}</>}</section>;
  const assessment = attempt.assessment || "wrong";
  const title = examTitle(attempt);
  const tags = attemptKnowledgeTags(attempt);
  return <Shell data={data}><main className="hub-main narrow practice-page"><button className="practice-exit" onClick={finish}>← 结束并返回</button><p className="eyebrow">{practiceLabel(session.intent)} · {attempt.targetKnowledgePointName}</p>
    {title&&<div className="practice-exam-meta">{attempt.examLabel&&<span className="practice-exam-label">{attempt.examLabel}</span>}{attempt.displayQuestionNumber&&<span className="practice-question-number">第{attempt.displayQuestionNumber}题</span>}{!attempt.examLabel&&attempt.sourceName&&<span className="practice-question-number">{attempt.sourceName}</span>}</div>}
    {tags.length>0&&<div className="tag-row">{tags.map(tag=><HubLink className={`knowledge-tag ${tag.role||"core"}`} href={`/knowledge/${tag.id}`} key={tag.id}>{tag.name}</HubLink>)}</div>}
    <h1>{attempt.evidenceMode === "remedial" ? "分步讲练" : attempt.evidenceMode === "training" ? "补救训练" : "正式练习"}</h1>{error && <p className="hub-error">{error}</p>}
    <section className="hub-panel rich"><RichText>{question.question}</RichText><div className="practice-options">{Object.entries(practiceOptions).map(([key, text]) => <button className={selected.includes(key) ? "selected" : ""} disabled={attempt.status !== "active"} key={key} onClick={() => toggle(key)}><b>{key}.</b><RichText inline>{text}</RichText></button>)}</div></section>
    {attempt.status === "active" && question.gradingMode === "auto" && <button className="hub-primary" disabled={!selected.length} onClick={submit}>提交答案</button>}
    {attempt.status === "active" && question.gradingMode === "self_assessment" && <button className="hub-primary" onClick={() => update(platformApi.revealPractice(session))}>查看参考答案并自评</button>}
    {attempt.status === "graded" && <section className={`practice-result ${assessment}`}><h2>{assessment === "correct" ? "✓ 回答正确" : assessment === "partial" ? "△ 部分正确" : "✕ 回答错误"}</h2><p>{session.flowComplete ? session.canRepeat ? (session.intent === "wrong_drill" ? "还有没练过的错题，可以继续下一道。" : "当前知识点流程已经完成。") : "本轮可练题目已完成" : "继续进入同一套诊断或补救流程。"}</p><div className="practice-actions">{session.flowComplete ? <>{session.canRepeat&&<button className="hub-primary" onClick={() => update(platformApi.nextPractice(id))}>下一道题</button>}<button onClick={finish}>{session.intent === "wrong_review" || session.intent === "wrong_drill" ? "返回错题列表" : "结束专项"}</button></> : <button className="hub-primary" onClick={() => update(platformApi.nextPractice(id))}>继续下一题</button>}</div></section>}
    {attempt.status === "revealed" && <>{answerDetails}<div className="practice-assessment"><button onClick={() => update(platformApi.assessPractice(session, "correct"))}>完全正确</button><button onClick={() => update(platformApi.assessPractice(session, "partial"))}>部分正确</button><button onClick={() => update(platformApi.assessPractice(session, "wrong"))}>需要重学</button></div></>}
    {attempt.status === "graded" && answerDetails}
  </main></Shell>;
}

function QuestionPage({ data, id }: { data: HubBootstrap; id: string }) {
  const [question, setQuestion] = useState<BrowseQuestion>(); const [error, setError] = useState(""); const [showAnswer, setShowAnswer] = useState(false);
  useEffect(() => { platformApi.question(id).then(setQuestion).catch(e => setError(e.message)); }, [id]);
  const solution = question?.questionType === "solution";
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/books">← 返回题库</HubLink>{error && <p className="hub-error">{error}</p>}{question && <><p className="eyebrow">只读题目浏览 · {question.sourceName}</p><h1>{questionTitle(question)}</h1><ReadonlyQuestion question={question}/><button className="hub-primary" onClick={() => setShowAnswer(v => !v)}>{showAnswer ? "收起答案与解析" : "查看答案与解析"}</button>{showAnswer && <section className="hub-panel rich">{solution ? <><h2>参考解析</h2><RichText>{question.analysisMarkdown}</RichText></> : <><h2>参考答案</h2><AnswerDisplay standard={question.correctAnswer} presentationType={question.presentationType} options={Object.fromEntries((question.options || []).map(option => [option.key, option.text]))}/><h2>解析</h2><RichText>{question.analysisMarkdown}</RichText></>}</section>}</>}</main></Shell>;
}

function AccountPage({ data }: { data: HubBootstrap }) { return <Shell data={data}><main className="hub-main narrow"><HubLink href="/">← 返回万境中枢</HubLink><h1>学习账号</h1><section className="hub-panel"><p>显示名称：{data.learner.displayName}</p><p>用户名：{data.learner.username}</p><button onClick={async () => { await platformApi.logout(); go("/login"); }}>退出登录</button></section></main></Shell>; }

function AuthenticatedPlatform() {
  const path = useCurrentLocation().split(/[?#]/)[0];
  const [data, setData] = useState<HubBootstrap>(); const [error, setError] = useState("");
  const load = async () => { try { setData(await platformApi.bootstrap()); } catch (reason) { if (reason instanceof HttpError && reason.status === 401) go("/login"); else setError((reason as Error).message); } };
  useEffect(() => { void load(); }, []);
  if (!data) return <main className="hub-loading">{error || "正在载入万境中枢…"}</main>;
  if (path === "/worlds/ancient-official") return <div className="world-shell"><HubLink className="world-shell-home" href="/">← 万境中枢</HubLink><App /></div>;
  if (path === "/study") return <StudyPage data={data} reload={load} />;
  if (path.startsWith("/progress/books/") && path.includes("/chapters/")) { const parts=path.split("/"); return <ProgressChapterPage data={data} bookId={parts[3]} chapterId={parts[5]} />; }
  if (path.startsWith("/progress/books/")) return <ProgressBookPage data={data} bookId={path.split("/")[3]} />;
  if (path === "/progress") return <ProgressPage data={data} />;
  if (path === "/statistics") return <StatisticsPage data={data} />;
  if (path === "/reviews") { history.replaceState(null, "", "/study"); return <StudyPage data={data} reload={load} />; }
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
  const path = useCurrentLocation().split(/[?#]/)[0];
  if (path === "/login") return <AuthPage register={false} />;
  if (path === "/register") return <AuthPage register />;
  return <AuthenticatedPlatform />;
}

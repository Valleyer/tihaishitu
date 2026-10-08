import { useEffect, useRef, useState } from "react";
import type { CSSProperties, FormEvent } from "react";
import App from "../App";
import { RichText } from "../components/RichText";
import { Modal } from "../components/Modal";
import { HttpError } from "../api/http";
import { PAGE_SIZE } from "../pagination";
import { platformApi, type BookDetail, type BrowseQuestion, type HubBootstrap, type KnowledgeDirectoryItem, type KnowledgePoint, type KnowledgeState, type LearnerProgress, type PracticeSession, type ProgressChapter, type QuestionDirectoryFacets, type RecentChapter, type StudyProfile, type WrongQuestion } from "./api";
import { progressBandLabels } from "./progressView";
import { ActivityChart, OutcomeDistribution, progressMetrics } from "./ProgressPanels";
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
/**
 * 只读题目标题取自来源与显示题号，不再使用 legacy subject_name 作为用户可见路径。
 * 年份已经由 ReadonlyQuestion 的 question-meta 展示，这里不重复。
 */
const questionTitle = (question: BrowseQuestion) =>
  [question.sourceName || sourceTypeLabel[question.sourceType] || "题目",
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
      <HubLink className="auth-brand" href="/"><img src="/brand-logo.png" alt="" />万境书院</HubLink>
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
    ["首页", "/"], ["学习", "/study"], ["进度", "/progress"],
    ["知识", "/books"], ["题库", "/questions"],
  ];
  const active = (href: string) => href === "/" ? path === "/" : path === href || path.startsWith(`${href}/`);
  return <div className="learning-hub">
    <header className="hub-header"><div className="hub-header-inner"><HubLink className="hub-brand" href="/"><img src="/brand-logo.png" alt="" />万境书院</HubLink>
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
      <section className="quick-links"><div><HubLink href="/study"><b>章节知识练习</b><span>按文集和章节系统推进</span></HubLink><HubLink href="/wrong-questions"><b>错题本</b><span>长期保留并反复训练历史错题</span></HubLink><HubLink href="/books"><b>浏览知识</b><span>按知识点查看完整知识目录</span></HubLink><HubLink href="/questions"><b>浏览题库</b><span>查看全平台已发布题目与解析</span></HubLink></div></section></div>
  </main></Shell>;
}

function ProgressChapterTree({ chapter, bookId }: { chapter: ProgressChapter; bookId: string }) {
  return <li><HubLink href={`/progress/books/${bookId}/chapters/${chapter.chapterId}`}><span>{chapter.name}</span><small>掌握进度 {Math.round(chapter.masteryProgress)}% · {chapter.total} 个知识点</small></HubLink>
  </li>;
}

export function ProgressPage({ data }: { data: HubBootstrap }) {
  const [progress, setProgress] = useState<LearnerProgress>(); const [error, setError] = useState("");
  useEffect(() => { platformApi.progress().then(setProgress).catch(reason => setError((reason as Error).message)); }, []);
  const activity = progress?.activity; const metrics = activity && progressMetrics(activity.metrics);
  return <Shell data={data}><main className="hub-main progress-page">
    <header className="progress-head">
      <div><p className="eyebrow">进度</p><h1>学习进度</h1></div>
      <div className="progress-scope">{progress && <><span className="scope-badge">{progress.summary.selectedBooks} 本文集</span><span>当前学习范围</span><small>{progress.summary.totalKnowledgePoints} 个可学习知识点</small></>}</div>
    </header>
    {error && <p className="hub-error" role="alert">{error}</p>}
    {!progress && !error && <p className="muted">正在整理学习进度…</p>}
    {progress && activity && metrics && <>
      <section className="progress-metrics" aria-label="六个核心指标">
        {metrics.map(metric => <article key={metric.label}>
          <span className="metric-label">{metric.label}</span>
          <b>{metric.value}<small>{metric.unit}</small></b>
          <em>{metric.hint}</em>
        </article>)}
      </section>
      <ActivityChart daily={activity.daily} />
      <OutcomeDistribution outcomes={activity.outcomes} />
      <section className="progress-section">
        <header className="section-heading"><h2>学习足迹</h2><span>最近接触的知识点</span></header>
        {progress.recent.knowledgePoints.length === 0
          ? <div className="hub-panel empty-state"><h3>还没有学习记录</h3><p>从一次章节练习或知识点专项开始，足迹会出现在这里。</p><HubLink className="hub-primary" href="/study">开始学习</HubLink></div>
          : <div className="recent-points">{progress.recent.knowledgePoints.map(point =>
            <HubLink className="hub-panel" href={`/knowledge/${point.knowledgePointId}`} key={point.knowledgePointId}>
              <div><h3>{point.name}</h3><p>{point.bookName} · {point.chapterName}</p></div>
              <div className="recent-point-state">
                <span className={`mastery-band ${point.band}`}>{progressBandLabels[point.band]}</span>
                <b>{Math.round(point.effectiveMastery)}%</b>
                <small>{recentTime(point.lastEvidenceAt)}</small>
              </div>
            </HubLink>)}</div>}
      </section>
      <section className="progress-section">
        <header className="section-heading"><h2>文集掌握进度</h2><span>掌握度按范围内知识点的有效掌握平均值</span></header>
        <div className="progress-books">
          {progress.books.map(book => <article className="hub-panel progress-book-card" key={book.bookId}>
            <div className="progress-book-head"><h3>{book.name}</h3><b>{Math.round(book.masteryProgress)}%</b></div>
            <div className="mastery-progress"><span style={{ width: `${book.masteryProgress}%` }} /></div>
            <small>{book.totalKnowledgePoints} 个知识点 · 已开始 {book.started} · 熟练掌握及以上 {book.ready} · 彻底掌握 {book.proficient}</small>
            <HubLink href={`/progress/books/${book.bookId}`}>查看章节进度 →</HubLink>
          </article>)}
        </div>
        {progress.books.length === 0 && <section className="hub-panel"><h2>尚未选择学习文集</h2><p>先到学习页设置学习范围，进度与统计会立即按新范围重新派生。</p><HubLink className="hub-primary" href="/study">设置学习范围</HubLink></section>}
      </section>
    </>}
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
      <article className="today-card"><div className="section-heading"><h2>学习状态</h2></div>{progress?<div className="today-metrics"><p><b>{progress.summary.startedKnowledgePoints}</b><span>已开始知识点</span></p><p><b>{progress.summary.readyKnowledgePoints}</b><span>熟练掌握及以上</span></p><p><b>{progress.summary.wrongQuestions}</b><span>错题本题目</span></p><p><b>{progress.activity.metrics.todayEffectiveAttempts}</b><span>今日答题</span></p><p><b>{progress.activity.metrics.activeStudyDays7d}</b><span>近 7 天活跃学习日</span></p></div>:<p className="muted">{progress===null?"暂时无法读取学习状态":"正在整理学习状态…"}</p>}</article></section>
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

/**
 * `/statistics` 的兼容重定向。
 *
 * <p>PR7 起进度与统计是同一个模块：顶栏只保留「进度」。旧链接与书签仍可访问，这里用
 * `replaceState` 替换 URL，避免留下会在返回时再次触发的历史条目，也不会渲染第二套页面
 * 或发起重复请求。</p>
 */
function StatisticsRedirect({ data }: { data: HubBootstrap }) {
  useEffect(() => { history.replaceState(null, "", "/progress"); }, []);
  return <ProgressPage data={data} />;
}

const bandLabel: Record<KnowledgeState["band"], string> = progressBandLabels;

function ChapterSection({ chapter, bookId, states }: { chapter: BookDetail["chapters"][number]; bookId: string; states: Map<string, KnowledgeState> }) {
  return <section className="hub-panel"><h2>{chapter.name}</h2><p>{chapter.description}</p><div className="knowledge-links">{chapter.knowledgePoints.map(point => { const state = states.get(point.id); return <HubLink href={`/knowledge/${point.id}?bookId=${encodeURIComponent(bookId)}&chapterId=${encodeURIComponent(chapter.id)}`} key={point.id}>{point.name}<small>{point.description}</small><span className={`mastery-band ${state?.band || "unstarted"}`}>{bandLabel[state?.band || "unstarted"]}{state?.evidenceCount ? ` · ${state.effectiveMastery.toFixed(1)}%` : ""}</span></HubLink> })}</div></section>;
}

function BookPage({ data, id }: { data: HubBootstrap; id: string }) {
  const [book, setBook] = useState<BookDetail>(); const [states, setStates] = useState(new Map<string, KnowledgeState>()); const [error, setError] = useState("");
  useEffect(() => { Promise.all([platformApi.book(id), platformApi.knowledgeStatesForBook(id)]).then(([value, stateList]) => { setBook(value); setStates(new Map(stateList.map(state => [state.knowledgePointId, state]))) }).catch(e => setError(e.message)); }, [id]);
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/books">← 返回知识</HubLink>{error && <p className="hub-error">{error}</p>}{book && <><h1>{book.name}</h1><p>{book.description}</p><p>{book.knowledgePointCount} 个知识点 · {book.questionCount} 道已发布题目</p>{book.chapters.map(chapter => <ChapterSection chapter={chapter} bookId={book.id} states={states} key={chapter.id} />)}</>}</main></Shell>;
}

/**
 * 只读题目的知识点标签。
 *
 * <p>{@code link} 默认关闭：全平台题库可能出现 Learner 尚未选择的 Book 下的知识点，
 * 跳 {@code /knowledge/{id}} 会 404，所以全局题库场景只渲染标签、不做链接。
 * 已经确定学习范围的页面（KnowledgePage / Practice 等）继续传 {@code true} 保持可点击。</p>
 */
function KnowledgeTags({ points, link }: { points?: BrowseQuestion["knowledgePoints"]; link: boolean }) {
  return <div className="tag-row">{points?.map(point => link
    ? <HubLink className={`knowledge-tag ${point.role||"core"}`} href={`/knowledge/${point.id}`} key={point.id}>{point.name}</HubLink>
    : <span className={`knowledge-tag ${point.role||"core"}`} key={point.id}>{point.name}</span>)}</div>;
}

function ReadonlyQuestion({ question, linkKnowledgePoints = false }: { question: BrowseQuestion; linkKnowledgePoints?: boolean }) { return <><div className="question-meta"><span>{question.examYear||""}{question.displayQuestionNumber?` · 第 ${question.displayQuestionNumber} 题`:""}</span><span>{questionTypeLabel[question.questionType] || "题目"}</span><span>难度 {question.difficulty}</span></div><section className="hub-panel rich"><RichText>{question.contentMarkdown}</RichText>{question.options?.map(option=><p className="readonly-option" key={option.key}><b>{option.key}.</b> <RichText inline>{option.text}</RichText></p>)}</section><KnowledgeTags points={question.knowledgePoints} link={linkKnowledgePoints}/></>; }

export function QuestionPreviewCard({ summary, linkKnowledgePoints = false }: { summary: BrowseQuestion; linkKnowledgePoints?: boolean }) { const [open,setOpen]=useState(false); const [question,setQuestion]=useState<BrowseQuestion>(); const [error,setError]=useState(""); const toggle=async()=>{if(!open&&!question){try{setQuestion(await platformApi.question(summary.id))}catch(reason){setError((reason as Error).message)}}setOpen(v=>!v)}; return <article className="hub-panel question-summary"><div><p>{summary.sourceName||sourceTypeLabel[summary.sourceType]||"题目"}{summary.examYear?` · ${summary.examYear}`:""}{summary.displayQuestionNumber?` · 第${summary.displayQuestionNumber}题`:""}{summary.learnerQuestionStatus==="mastered"&&<span className="question-mastered">✓ 已掌握</span>}</p><h3><RichText>{summary.contentMarkdown}</RichText></h3><KnowledgeTags points={summary.knowledgePoints} link={linkKnowledgePoints}/></div><div className="question-summary-actions"><button onClick={toggle}>{open?"收起预览":"预览"}</button><HubLink href={`/questions/${summary.id}`}>查看答案与解析</HubLink></div>{error&&<p className="hub-error" role="alert">{error}</p>}{open&&question&&<div className="inline-question-preview"><ReadonlyQuestion question={question} linkKnowledgePoints={linkKnowledgePoints}/></div>}</article>; }

function KnowledgePage({ data, id }: { data: HubBootstrap; id: string }) {
  const [point,setPoint]=useState<(KnowledgePoint&{books:{id:string;name:string;chapterId:string;chapterName:string}[]})>(); const [questions,setQuestions]=useState<BrowseQuestion[]>([]); const [state,setState]=useState<KnowledgeState>(); const [neighbors,setNeighbors]=useState<Awaited<ReturnType<typeof platformApi.knowledgeNeighbors>>>(); const [guide,setGuide]=useState<Awaited<ReturnType<typeof platformApi.knowledgeGuide>>>(); const [guideOpen,setGuideOpen]=useState(false); const [error,setError]=useState("");
  useEffect(()=>{Promise.all([platformApi.knowledge(id),platformApi.knowledgeQuestions(id),platformApi.knowledgeState(id)]).then(([p,q,s])=>{setPoint(p);setQuestions(q);setState(s)}).catch(e=>setError(e.message))},[id]);
  const requestedBook=new URLSearchParams(window.location.search).get("bookId"); const requestedChapter=new URLSearchParams(window.location.search).get("chapterId");
  const context=point?.books.find(item=>item.id===requestedBook&&item.chapterId===requestedChapter)||point?.books[0];
  useEffect(()=>{if(context)platformApi.knowledgeNeighbors(id,context.id,context.chapterId).then(setNeighbors).catch(()=>setNeighbors(undefined))},[id,context?.id,context?.chapterId]);
  const contextQuery=context?`?bookId=${encodeURIComponent(context.id)}&chapterId=${encodeURIComponent(context.chapterId)}`:"";
  const start=async()=>{try{const session=await platformApi.startKnowledgePractice(id);go(practicePath(session.id,`/knowledge/${id}${contextQuery}`))}catch(reason){setError((reason as Error).message)}};
  const openGuide=async()=>{setGuideOpen(true);try{setGuide(await platformApi.knowledgeGuide(id))}catch(reason){setError((reason as Error).message)}};
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/books">← 返回知识</HubLink>{error&&<p className="hub-error">{error}</p>}{point&&<><p className="eyebrow">{context?`${context.name} · ${context.chapterName}`:"知识点"}</p><h1>{point.name}</h1><div className="knowledge-actions"><button className="hub-primary" onClick={start}>开始知识点练习</button><button onClick={openGuide}>知识讲解</button>{neighbors?.next?<HubLink href={`/knowledge/${neighbors.next.id}?bookId=${encodeURIComponent(context!.id)}&chapterId=${encodeURIComponent(neighbors.next.chapterId)}`}>下一个知识点 →</HubLink>:<span className="muted">已到文集末尾</span>}</div>{state&&<section className={`hub-panel mastery-summary ${state.band==="proficient"?"mastery-perfect":""}`}><h2>当前状态</h2><p className="mastery-score"><b>{state.band==="proficient"?"✦ ":""}{bandLabel[state.band]}</b> · {state.effectiveMastery.toFixed(1)}%</p><p>{state.evidenceCount?`最近练习：${state.lastEvidenceAt?new Date(state.lastEvidenceAt).toLocaleDateString("zh-CN"):"—"}`:"尚未开始正式训练"}</p></section>}<h2>相关正式真题</h2><div className="question-preview-list">{questions.map(question=><QuestionPreviewCard summary={question} linkKnowledgePoints key={question.id}/>)}</div>{questions.length===0&&<p className="empty-state">当前没有已发布题目。</p>}{guideOpen&&<Modal title={`${point.name} · 知识讲解`} wide close={()=>setGuideOpen(false)}>{guide===undefined?<p>正在载入知识讲解…</p>:guide.contentMarkdown?<div className="rich"><RichText>{guide.contentMarkdown}</RichText></div>:<div className="empty-state"><h3>知识讲解尚未录入</h3></div>}</Modal>}</>}</main></Shell>;
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
    {items.length === 0 && <section className="hub-panel"><h2>当前学习范围内暂无可练错题</h2><p className="muted">切换回曾经学习的文集后，保留的错题会自动恢复显示。</p></section>}
    <div className="wrong-cards">{items.map(item => <article className="hub-panel" key={item.questionId}>{item.examLabel&&<div className="practice-exam-meta"><span className="practice-exam-label">{item.examLabel}</span>{item.displayQuestionNumber&&<span className="practice-question-number">第{item.displayQuestionNumber}题</span>}</div>}<h2>{item.knowledgePointName}</h2>{item.knowledgePoints&&item.knowledgePoints.length>0&&<div className="tag-row">{item.knowledgePoints.map(point=><HubLink className={`knowledge-tag ${point.role||"core"}`} href={`/knowledge/${point.id}`} key={point.id}>{point.name}</HubLink>)}</div>}<div className="wrong-question-content"><RichText>{item.contentMarkdown}</RichText></div><small>最近做错：{new Date(item.lastGradedAt).toLocaleString("zh-CN", { hour12: false })}</small>{!item.available&&<p className="muted">{wrongQuestionUnavailableLabel(item.unavailableReason)}</p>}<div className="wrong-actions"><button className="hub-primary" disabled={!item.available} onClick={() => start(item.questionId)}>重做这道题</button><button className="secondary" onClick={() => remove(item.questionId)}>移出错题本</button></div></article>)}</div>
  </main></Shell>;
}

export function PracticePage({ data, id }: { data: HubBootstrap; id: string }) {
  const [session, setSession] = useState<PracticeSession>(); const [selected, setSelected] = useState<string[]>([]); const [error, setError] = useState("");
  const [reportOpen,setReportOpen]=useState(false); const [reportReason,setReportReason]=useState("content_error"); const [reportComment,setReportComment]=useState(""); const [reportStatus,setReportStatus]=useState("");
  const reportAttemptId=useRef<string | undefined>(undefined);
  const applySession=(value:PracticeSession)=>{
    const nextAttemptId=value.currentAttempt.id;
    if(reportAttemptId.current&&reportAttemptId.current!==nextAttemptId){
      setReportOpen(false); setReportReason("content_error"); setReportComment(""); setReportStatus("");
    }
    reportAttemptId.current=nextAttemptId; setSession(value); setSelected([]); setError("");
  };
  const load = () => platformApi.practice(id).then(applySession).catch(reason => setError((reason as Error).message));
  useEffect(() => { void load(); }, [id]);
  if (!session) return <Shell data={data}><main className="hub-main narrow"><p>{error || "正在恢复专项练习…"}</p></main></Shell>;
  const attempt = session.currentAttempt; const question = attempt.question;
  const multiple = question.presentationType === "multiple_choice";
  const practiceOptions = Object.keys(question.options || {}).length
    ? question.options : question.presentationType === "true_false" ? { true: "正确", false: "错误" } : {};
  const toggle = (key: string) => setSelected(values => multiple ? (values.includes(key) ? values.filter(value => value !== key) : [...values, key]) : [key]);
  const submit = async () => { try {
    const answer = question.presentationType === "true_false" ? selected[0] === "true" : multiple ? selected : selected[0];
    applySession(await platformApi.answerPractice(session, answer));
  } catch (reason) { setError((reason as Error).message); } };
  const update = (action: Promise<PracticeSession>) => action.then(applySession).catch(reason => setError((reason as Error).message));
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
    {attempt.status === "active" && question.gradingMode === "auto" && <div className="practice-actions"><button onClick={()=>update(platformApi.noIdeaPractice(session))}>我没思路</button><button className="hub-primary" disabled={!selected.length} onClick={submit}>提交答案</button></div>}
    {attempt.status === "active" && question.gradingMode === "self_assessment" && <div className="practice-actions"><button onClick={()=>update(platformApi.noIdeaPractice(session))}>我没思路</button><button className="hub-primary" onClick={() => update(platformApi.revealPractice(session))}>查看参考解析并自评</button></div>}
    {attempt.status === "graded" && <section className={`practice-result ${assessment}`}><h2>{assessment === "correct" ? "✓ 回答正确" : assessment === "partial" ? "△ 部分正确" : "✕ 回答错误"}</h2><p>{session.flowComplete ? session.canRepeat ? (session.intent === "wrong_drill" ? "还有没练过的错题，可以继续下一道。" : session.intent === "chapter_drill" ? "章节还有后续题目，可以继续下一道。" : "当前知识点流程已经完成。") : "本轮可练题目已完成" : "继续下一题。"}</p><div className="practice-actions">{session.flowComplete ? <>{session.canRepeat&&<button className="hub-primary" onClick={() => update(platformApi.nextPractice(id))}>下一道题</button>}<button onClick={finish}>{session.intent === "wrong_review" || session.intent === "wrong_drill" ? "返回错题列表" : "结束专项"}</button></> : <button className="hub-primary" onClick={() => update(platformApi.nextPractice(id))}>继续下一题</button>}</div></section>}
    {attempt.status === "revealed" && <>{answerDetails}<div className="practice-assessment"><button onClick={()=>update(platformApi.noIdeaPractice(session))}>我没思路</button><button onClick={() => update(platformApi.assessPractice(session, "correct"))}>完全正确</button><button onClick={() => update(platformApi.assessPractice(session, "partial"))}>部分正确</button><button onClick={() => update(platformApi.assessPractice(session, "wrong"))}>需要重学</button></div></>}
    {attempt.status === "graded" && answerDetails}
    <button className="text-button question-report-trigger" onClick={()=>setReportOpen(true)}>题目有误？</button>
    {reportOpen&&<Modal title="题目有误？" subtitle="反馈不会影响本次作答" close={()=>setReportOpen(false)}><form className="question-report-form" onSubmit={event=>{event.preventDefault();setReportStatus("提交中…");platformApi.reportQuestion(attempt.id,reportReason,reportComment).then(()=>setReportStatus("已收到反馈")).catch(reason=>setReportStatus((reason as Error).message))}}><label>问题类型<select value={reportReason} onChange={event=>setReportReason(event.target.value)}><option value="content_error">题干有误</option><option value="answer_error">答案有误</option><option value="analysis_error">解析有误</option><option value="format_error">排版有误</option><option value="other">其他</option></select></label><label>补充说明（可空）<textarea maxLength={1000} value={reportComment} onChange={event=>setReportComment(event.target.value)}/></label><button className="hub-primary" disabled={reportStatus==="提交中…"||reportStatus==="已收到反馈"}>提交</button>{reportStatus&&<p role="status">{reportStatus}</p>}</form></Modal>}
  </main></Shell>;
}

/**
 * 全平台题库：所有 published Formal Parent Question 的只读浏览。
 *
 * <p>它是全局浏览，与 Learner 当前 selected Books 解耦：没有选中文集也能看题，
 * 但浏览不创建 Attempt、不影响 Mastery / Wrong Book / RANDOM 每日额度。
 * 分页固定每页 20 条，不提供 page-size 自选控件。</p>
 */
export function QuestionDirectoryPage({ data }: { data: HubBootstrap }) {
  const [query,setQuery]=useState(""); const [sourceId,setSourceId]=useState(""); const [examYear,setExamYear]=useState("");
  const [questionType,setQuestionType]=useState(""); const [difficulty,setDifficulty]=useState("");
  const [bookId,setBookId]=useState(""); const [chapterId,setChapterId]=useState(""); const [knowledge,setKnowledge]=useState("");
  const [facets,setFacets]=useState<QuestionDirectoryFacets>();
  const [items,setItems]=useState<BrowseQuestion[]>([]); const [error,setError]=useState("");
  const [page,setPage]=useState(0); const [totalElements,setTotalElements]=useState(0); const [totalPages,setTotalPages]=useState(0);
  useEffect(()=>{platformApi.questionDirectoryFacets().then(setFacets).catch(reason=>setError((reason as Error).message))},[]);
  useEffect(()=>{
    let cancelled=false;
    platformApi.questionDirectory({query,sourceId,examYear,questionType,difficulty,bookId,chapterId,knowledge,page,size:PAGE_SIZE})
      .then(result=>{if(cancelled)return;setItems(result.content);setTotalElements(result.totalElements);setTotalPages(result.totalPages);setError("")})
      .catch(reason=>{if(!cancelled)setError((reason as Error).message)});
    return ()=>{cancelled=true};
  },[query,sourceId,examYear,questionType,difficulty,bookId,chapterId,knowledge,page]);
  /** 任何筛选变化都必须把 page 归零，否则会停在旧页码的空页上。 */
  const change=(setter:(value:string)=>void)=>(value:string)=>{setter(value);setPage(0)};
  const chapters=facets?.books.find(book=>book.id===bookId)?.chapters||[];
  const reset=()=>{setQuery("");setSourceId("");setExamYear("");setQuestionType("");setDifficulty("");setBookId("");setChapterId("");setKnowledge("");setPage(0)};
  return <Shell data={data}><main className="hub-main question-bank-page">
    <header className="page-title"><div><h1>题库</h1><p>全平台已发布正式题目，共 {totalElements} 道</p></div></header>
    {error&&<p className="hub-error" role="alert">{error}</p>}
    <div className="bank-filters question-directory-filters">
      <input aria-label="关键词" placeholder="搜索题干 / 来源 / 题号（支持 2020-7）" value={query} onChange={e=>change(setQuery)(e.target.value)}/>
      <select aria-label="来源" value={sourceId} onChange={e=>change(setSourceId)(e.target.value)}><option value="">全部来源</option>{facets?.sources.map(source=><option value={source.id} key={source.id}>{source.displayName}</option>)}</select>
      <select aria-label="年份" value={examYear} onChange={e=>change(setExamYear)(e.target.value)}><option value="">全部年份</option>{facets?.examYears.map(year=><option value={year} key={year}>{year}</option>)}</select>
      <select aria-label="题型" value={questionType} onChange={e=>change(setQuestionType)(e.target.value)}><option value="">全部题型</option>{Object.entries(questionTypeLabel).map(([value,label])=><option value={value} key={value}>{label}</option>)}</select>
      <select aria-label="难度" value={difficulty} onChange={e=>change(setDifficulty)(e.target.value)}><option value="">全部难度</option>{[1,2,3,4,5].map(value=><option value={value} key={value}>难度 {value}</option>)}</select>
      <select aria-label="文集" value={bookId} onChange={e=>{setBookId(e.target.value);setChapterId("");setPage(0)}}><option value="">全部文集</option>{facets?.books.map(book=><option value={book.id} key={book.id}>{book.name}</option>)}</select>
      <select aria-label="章节" value={chapterId} disabled={!bookId} onChange={e=>change(setChapterId)(e.target.value)}><option value="">全部章节</option>{chapters.map(chapter=><option value={chapter.id} key={chapter.id}>{chapter.name}</option>)}</select>
      <input aria-label="知识点" placeholder="知识点 ID / 编码 / 名称" value={knowledge} onChange={e=>change(setKnowledge)(e.target.value)}/>
      <button onClick={reset}>重置筛选</button>
    </div>
    {items.length===0&&!error&&<p className="empty-state">没有符合条件的题目。</p>}
    <div className="question-preview-list">{items.map(question=><QuestionPreviewCard summary={question} key={question.id}/>)}</div>
    <div className="directory-pagination"><span>共 {totalElements} 道题</span><span>第 {totalPages?page+1:0} / {totalPages} 页</span><button disabled={page===0} onClick={()=>setPage(value=>value-1)}>上一页</button><button disabled={!totalPages||page>=totalPages-1} onClick={()=>setPage(value=>value+1)}>下一页</button></div>
  </main></Shell>;
}

export function QuestionPage({ data, id }: { data: HubBootstrap; id: string }) {
  const [question, setQuestion] = useState<BrowseQuestion>(); const [error, setError] = useState(""); const [showAnswer, setShowAnswer] = useState(false);
  useEffect(() => { platformApi.question(id).then(setQuestion).catch(e => setError(e.message)); }, [id]);
  const solution = question?.questionType === "solution";
  // 全平台题目详情：题目的知识点可能属于 Learner 尚未选择的文集，标签只展示、不跳转。
  return <Shell data={data}><main className="hub-main narrow"><HubLink href="/questions">← 返回题库</HubLink>{error && <p className="hub-error">{error}</p>}{question && <><p className="eyebrow">全平台题库 · 只读题目浏览</p><h1>{questionTitle(question)}</h1><ReadonlyQuestion question={question}/><button className="hub-primary" onClick={() => setShowAnswer(v => !v)}>{showAnswer ? "收起答案与解析" : "查看答案与解析"}</button>{showAnswer && <section className="hub-panel rich practice-answer">{solution ? <><h2>参考解析</h2><RichText>{question.analysisMarkdown}</RichText></> : <><h2>参考答案</h2><AnswerDisplay standard={question.correctAnswer} presentationType={question.presentationType} options={Object.fromEntries((question.options || []).map(option => [option.key, option.text]))}/><h2>解析</h2><RichText>{question.analysisMarkdown}</RichText></>}</section>}</>}</main></Shell>;
}

function AccountPage({ data }: { data: HubBootstrap }) { return <Shell data={data}><main className="hub-main narrow"><HubLink href="/">← 返回万境中枢</HubLink><h1>学习账号</h1><section className="hub-panel"><p>显示名称：{data.learner.displayName}</p><p>用户名：{data.learner.username}</p><button onClick={async () => { await platformApi.logout(); go("/login"); }}>退出登录</button></section></main></Shell>; }

export function AuthenticatedPlatform() {
  const path = useCurrentLocation().split(/[?#]/)[0];
  const [data, setData] = useState<HubBootstrap>(); const [error, setError] = useState("");
  const load = async () => { try { setData(await platformApi.bootstrap()); } catch (reason) { if (reason instanceof HttpError && reason.status === 401) go("/login"); else setError((reason as Error).message); } };
  useEffect(() => { void load(); }, []);
  if (!data) return <main className="hub-loading">{error || "正在载入万境中枢…"}</main>;
  if (path === "/worlds/ancient-official") return <div className="world-shell"><App /></div>;
  if (path === "/study") return <StudyPage data={data} reload={load} />;
  if (path.startsWith("/progress/books/") && path.includes("/chapters/")) { const parts=path.split("/"); return <ProgressChapterPage data={data} bookId={parts[3]} chapterId={parts[5]} />; }
  if (path.startsWith("/progress/books/")) return <ProgressBookPage data={data} bookId={path.split("/")[3]} />;
  if (path === "/progress") return <ProgressPage data={data} />;
  if (path === "/statistics") return <StatisticsRedirect data={data} />;
  if (path === "/reviews") { history.replaceState(null, "", "/study"); return <StudyPage data={data} reload={load} />; }
  if (path === "/wrong-questions") return <WrongQuestionsPage data={data} />;
  if (path.startsWith("/practice/")) return <PracticePage data={data} id={idAfter("/practice/")} />;
  if (path === "/books") return <BooksPage data={data} />;
  if (path.startsWith("/books/")) return <BookPage data={data} id={idAfter("/books/")} />;
  if (path.startsWith("/knowledge/")) return <KnowledgePage data={data} id={idAfter("/knowledge/")} />;
  if (path === "/questions") return <QuestionDirectoryPage data={data} />;
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

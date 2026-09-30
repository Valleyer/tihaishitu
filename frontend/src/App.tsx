import { useEffect, useState } from "react";
import { api } from "./api";
import type {
  Mistake,
  QuestionBank,
  Session,
  Statistics,
} from "./domain/types";
import "./App.css";

const tabs = ["书院", "题库", "旧案", "学业"] as const;
type Tab = (typeof tabs)[number];
function App() {
  const [tab, setTab] = useState<Tab>("书院");
  const [session, setSession] = useState<Session | null>(null);
  const [banks, setBanks] = useState<QuestionBank[]>([]);
  const [mistakes, setMistakes] = useState<Mistake[]>([]);
  const [stats, setStats] = useState<Statistics | null>(null);
  const [selected, setSelected] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    Promise.all([
      api.getSession(),
      api.getBanks(),
      api.getMistakes(),
      api.getStatistics(),
    ])
      .then(([next, nextBanks, nextMistakes, nextStats]) => {
        if (!active) return;
        setSession(next);
        setBanks(nextBanks);
        setMistakes(nextMistakes);
        setStats(nextStats);
      })
      .catch((reason) => {
        if (active)
          setError(
            reason instanceof Error ? reason.message : "读取失败，请刷新重试。",
          );
      });
    return () => {
      active = false;
    };
  }, []);
  async function advance() {
    if (!session || busy) return;
    setBusy(true);
    setError("");
    try {
      const next = session.result
        ? await api.nextQuestion(session.attemptId)
        : await api.submitAnswer({
            attemptId: session.attemptId,
            questionId: session.question.id,
            answer: selected,
          });
      setSession(next);
      setSelected("");
      const [nextMistakes, nextStats] = await Promise.all([
        api.getMistakes(),
        api.getStatistics(),
      ]);
      setMistakes(nextMistakes);
      setStats(nextStats);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "操作失败，请重试。");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          aria-label="题海仕途首页"
          onClick={(event) => {
            event.preventDefault();
            setTab("书院");
          }}
        >
          <span className="seal">题</span>
          <span>
            题海仕途<small>一卷书，一生路。</small>
          </span>
        </a>
        <div className="nav-caption">求学之路</div>
        <nav aria-label="主导航">
          {tabs.map((item, index) => (
            <button
              key={item}
              className={tab === item ? "nav-item active" : "nav-item"}
              aria-current={tab === item ? "page" : undefined}
              onClick={() => setTab(item)}
            >
              <span>0{index + 1}</span>
              {item}
              <span className="nav-arrow">↗</span>
            </button>
          ))}
        </nav>
        <div className="sidebar-note">
          <span>卷首寄语</span>
          <p>
            不积跬步，
            <br />
            无以至千里。
          </p>
          <small>你的前程，藏在下一题里。</small>
        </div>
        <div className="edition">题海仕途 · 初版演示</div>
      </aside>
      <div className="workspace">
        <header>
          <span>
            大周 <i /> 景和二十三年
          </span>
          <span className="header-tag">寒门求学篇</span>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <div className="eyebrow">
                {tab === "书院" ? "青灯照卷 · 静心求学" : "案头文书 · 点滴积累"}
              </div>
              <h1>
                {tab === "书院"
                  ? "今日，再进一寸。"
                  : {
                      题库: "胸中有丘壑。",
                      旧案: "温故，而知新。",
                      学业: "寸功，皆有迹。",
                    }[tab]}
              </h1>
              <p>从寒门到朝堂，前程一题一题答。</p>
            </div>
            <span className="chapter-stamp">
              求学
              <br />
              第一章
            </span>
          </div>
          {error && (
            <div className="error" role="alert">
              {error}
            </div>
          )}
          {!session && !error && <p role="status">正在展开书卷……</p>}
          {session && (
            <div className="content-grid">
              <section className="main-column">
                {tab === "书院" && (
                  <>
                    <div className="story-card">
                      <span className="eyebrow">书院日常</span>
                      <p>
                        晨光穿过窗棂，案上的书卷又翻过一页。先生将一份习题轻轻放下，示意你静心作答。
                      </p>
                      <span className="story-source">—— 求学札记</span>
                    </div>
                    <article className="question-card">
                      <div className="question-meta">
                        <span>
                          {session.question.subject} /{" "}
                          {session.question.chapter}
                        </span>
                        <span>单选</span>
                      </div>
                      <h2>{session.question.prompt}</h2>
                      <fieldset disabled={busy || !!session.result}>
                        <legend className="sr-only">选择答案</legend>
                        {session.question.options.map((option) => (
                          <label
                            key={option.id}
                            className={`option ${selected === option.id || session.result?.selected === option.id ? "selected" : ""} ${session.result?.correctAnswer === option.id ? "correct-option" : ""}`}
                          >
                            <input
                              type="radio"
                              name="answer"
                              value={option.id}
                              checked={
                                (session.result?.selected || selected) ===
                                option.id
                              }
                              onChange={() => setSelected(option.id)}
                            />
                            <span className="option-letter">{option.id}</span>
                            <span>{option.text}</span>
                          </label>
                        ))}
                      </fieldset>
                      {session.result && (
                        <div
                          className={`result ${session.result.correct ? "success" : ""}`}
                          role="status"
                        >
                          <strong>
                            {session.result.correct
                              ? "答对了，学有所获。"
                              : `此处需复习 · 正确答案 ${session.result.correctAnswer}`}
                          </strong>
                          <p>{session.result.explanation}</p>
                          <p className="result-story">{session.result.story}</p>
                          <small>
                            学识 +{session.result.knowledgeGain} · 本题已记录
                          </small>
                        </div>
                      )}
                      <div className="question-footer">
                        <span>
                          {session.result
                            ? "积少成多，不急于一时。"
                            : "静心思考，再落笔。"}
                        </span>
                        <button
                          className="primary"
                          disabled={busy || (!selected && !session.result)}
                          onClick={() => void advance()}
                        >
                          {busy
                            ? "正在收卷…"
                            : session.result
                              ? "下一题 →"
                              : "交卷 →"}
                        </button>
                      </div>
                    </article>
                    <p className="demo-note">
                      当前为 4
                      道示例题循环演示；完整题库与成长剧情将在后续扩充。
                    </p>
                  </>
                )}
                {tab === "题库" && (
                  <section className="panel">
                    <div className="eyebrow">我的题库</div>
                    {banks.map((bank) => (
                      <article className="list-item" key={bank.id}>
                        <h2>{bank.name}</h2>
                        <p>{bank.description}</p>
                        <span className="pill">
                          {bank.count} 道题 · 当前使用
                        </span>
                      </article>
                    ))}
                    <p className="muted">
                      当前提供示例题库。自定义题库与 JSON / CSV 导入尚未实现。
                    </p>
                  </section>
                )}
                {tab === "旧案" && (
                  <section className="panel">
                    <div className="eyebrow">
                      疑难旧案 · {mistakes.length} 题
                    </div>
                    {mistakes.length === 0 ? (
                      <div className="empty">
                        <h2>案头暂无疑难。</h2>
                        <p>答错的题目会留在这里，供你回看。</p>
                      </div>
                    ) : (
                      mistakes.map((item) => (
                        <article className="list-item" key={item.question.id}>
                          <span className="eyebrow">
                            {item.question.subject} · 累计错 {item.wrongCount}{" "}
                            次
                          </span>
                          <h2>{item.question.prompt}</h2>
                          <p>
                            正确答案：{item.correctAnswer} ·{" "}
                            {
                              item.question.options.find(
                                (option) => option.id === item.correctAnswer,
                              )?.text
                            }
                          </p>
                          <p>{item.explanation}</p>
                        </article>
                      ))
                    )}
                  </section>
                )}
                {tab === "学业" && stats && (
                  <section className="panel">
                    <div className="eyebrow">学习记录</div>
                    <div className="stats-grid">
                      {[
                        ["累计答题", stats.total],
                        ["今日答题", stats.today],
                        [
                          "正确率",
                          stats.total
                            ? `${Math.round((stats.correct / stats.total) * 100)}%`
                            : "—",
                        ],
                        ["连续答对", stats.streak],
                      ].map(([label, value]) => (
                        <div key={label}>
                          <span>{label}</span>
                          <strong>{value}</strong>
                        </div>
                      ))}
                    </div>
                    <p className="muted">
                      每次作答都是一份积累。今日答题按设备本地日期统计。
                    </p>
                  </section>
                )}
              </section>
              <aside className="right-column">
                <section className="profile panel">
                  <div className="eyebrow">人物小传</div>
                  <div className="avatar">
                    {session.player.name.slice(0, 1)}
                  </div>
                  <h2>{session.player.name}</h2>
                  <span className="pill">{session.player.title}</span>
                  <p>
                    家无显赫门第，
                    <br />
                    唯有案头万卷书。
                  </p>
                  <div className="profile-stat">
                    <span>科举学识</span>
                    <strong>{session.player.knowledge}</strong>
                  </div>
                  <div className="profile-stat">
                    <span>已答题目</span>
                    <strong>{stats?.total ?? 0}</strong>
                  </div>
                </section>
                <section className="goal panel">
                  <div className="eyebrow">眼下之志</div>
                  <h3>积学于书院</h3>
                  <p>先将眼前这一题读懂。功名之路，自此起步。</p>
                </section>
              </aside>
            </div>
          )}
        </main>
        <footer>
          题海仕途 <span>读书有所得，落笔有回响。</span>
        </footer>
      </div>
    </div>
  );
}
export default App;

import {
  WorldHub,
  ActivityShelf,
  ActivityDetail,
  NpcPanel,
  Inventory,
  Outcome,
  WorldGoals,
  ExamPanel,
} from "./components/Exploration";
import { effectiveAttribute } from "./engine/AdventureEngine";
/**
 * 页面总入口：协调首页、入章对话、答题主界面与案头弹窗。
 * 所有存档变更经 GameApi 执行；界面只接收已保存的结果，避免显示与存档不同步。
 * 世界观与关卡内容在 content/*.json；这里仅处理页面结构和交互。
 */
import { useEffect, useState } from "react";
import { api } from "./api";
import type { Bank, Bootstrap, Game, NewGame } from "./domain/types";
import { calendar, chapters } from "./engine/StoryEngine";
import { gameDesign, mapDesign, adventureDesign } from "./content";
import { Modal } from "./components/Modal";
import { Portrait } from "./components/Portrait";
import { NewGameForm } from "./components/NewGameForm";
import { QuestionPanel } from "./components/QuestionPanel";
import { Library } from "./components/Library";
import { download } from "./utils/download";
import { Journal, Reviews, Statistics } from "./components/Records";
import { ChapterGate } from "./components/ChapterGate";
import { DisplaySettings } from "./components/DisplaySettings";
import { WorldMap } from "./components/WorldMap";
import { isLearningMastered } from "./engine/SpacedRepetitionEngine";
import "./App.css";
import "./screen-fit.css";
import "./adventure.css";
type Panel =
  | "study"
  | "activity"
  | "bag"
  | "exam"
  | "new"
  | "library"
  | "saves"
  | "notes"
  | "journal"
  | "people"
  | "review"
  | "stats"
  | "display"
  | "map"
  | "story"
  | "event"
  | null;
const panelNames: Record<Exclude<Panel, null>, string> = {
  study: "点灯读书",
  activity: "一段行程",
  bag: "随身珍藏",
  exam: "青溪县试",
  new: "落笔入世",
  library: "藏书阁",
  saves: "人生存档",
  notes: "卷边批注",
  journal: "人生札记",
  people: "故人录",
  review: "疑难卷宗",
  stats: "修业簿",
  display: "游戏设置",
  map: "青溪县地图",
  story: "此间前情",
  event: "一念之间",
};
const dock = [
  { id: "study", icon: "书", label: "读书" },
  { id: "people", icon: "人", label: "故人" },
  { id: "library", icon: "册", label: "藏书阁" },
  { id: "review", icon: "卷", label: "旧案" },
  { id: "stats", icon: "业", label: "修业" },
  { id: "bag", icon: "囊", label: "行囊" },
  { id: "saves", icon: "档", label: "存档" },
] as const;
function App() {
  const [data, setData] = useState<Bootstrap | null>(null),
    [game, setGame] = useState<Game | null>(null),
    [panel, setPanel] = useState<Panel>(null);
  const [clock, setClock] = useState(() => new Date());
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [reviewOnly, setReview] = useState(false),
    [note, setNote] = useState("");
  const [savePage, setSavePage] = useState(0);
  const [npcFocus,setNpcFocus]=useState<string|undefined>();
  const meet=(id?:string)=>{setNpcFocus(id);show("people")};
  const [activityOpen, setActivityOpen] = useState(false),
    [detailId, setDetailId] = useState("read"),
    [settlement, setSettlement] = useState(false);
  useEffect(() => {
    let active = true;
    api
      .bootstrap()
      .then((value) => {
        if (active) setData(value);
      })
      .catch((reason) => {
        if (active) setError(String(reason.message || reason));
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    // 分钟级刷新足以覆盖日期与十二时辰变化，也避免无意义的每秒重绘。
    const timer = window.setInterval(() => setClock(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(""), 4000);
    return () => clearTimeout(timer);
  }, [notice]);
  async function sync() {
    setData(await api.bootstrap());
  }
  async function run(job: () => Promise<void>) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await job();
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "操作未完成，请重试。",
      );
    } finally {
      setBusy(false);
    }
  }
  /** 临时条件提示自动收起；若期间出现了新的错误，不会被旧计时器误清除。 */
  function reportTemporary(message: string) {
    setError(message);
    window.setTimeout(
      () => setError((current) => (current === message ? "" : current)),
      2000,
    );
  }
  async function changeBank(bank: Bank) {
    setBusy(true);
    setError("");
    try {
      await api.putBank(bank);
      await sync();
      setNotice("文集已收录");
    } catch (error) {
      setError((error as Error).message);
      throw error;
    } finally {
      setBusy(false);
    }
  }
  async function deleteBank(id: string) {
    setBusy(true);
    setError("");
    try {
      await api.deleteBank(id);
      await sync();
    } catch (error) {
      setError((error as Error).message);
      throw error;
    } finally {
      setBusy(false);
    }
  }
  const enter = (id: string) =>
    void run(async () => {
      setGame(await api.getGame(id));
      setReview(false);
      setActivityOpen(false);
      setSettlement(false);
      setPanel(null);
      await sync();
    });
  const start = (config: NewGame) =>
    void run(async () => {
      setGame(await api.createGame(config));
      setReview(false);
      setPanel(null);
      await sync();
    });
  function show(next: Panel) {
    setError("");
    setPanel(next);
    if (next === "saves")
      void sync().catch((reason) => setError(reason.message));
    if (next === "notes" && game?.attempt)
      setNote(game.notes[game.attempt.question.id] || "");
  }
  const chapter = game ? chapters[game.chapter] : null,
    date = game ? calendar(clock) : null,
    attempt = game?.attempt;
  const opening =
    game &&
    chapter &&
    !game.flags.includes("chapter-intro:" + chapter.id) &&
    !attempt?.result &&
    !game.adventure?.run;
  const location = game
    ? mapDesign.locations.find((l) => l.id === game.adventure?.locationId) ||
      mapDesign.locations[0]
    : null;
  const activityRun = game?.adventure?.run;
  const inspect = (id: string) => {
    setDetailId(id);
    show("activity");
  };
  const travel = (id: string) =>
    void run(async () => {
      if (!game) return;
      const updated = await api.travel(game.id, id);
      setGame(updated);
      setActivityOpen(false);
      if (updated.adventure?.encounter) {
        setDetailId(updated.adventure.encounter);
        setPanel("activity");
      } else setPanel(null);
    });
  const begin = () =>
    void run(async () => {
      if (!game) return;
      setGame(await api.beginActivity(game.id, detailId));
      setSettlement(false);
      setActivityOpen(true);
      setPanel(null);
    });
  const register = (id: string) =>
    void run(async () => {
      if (!game) return;
      setGame(await api.registerExam(game.id, id));
      setNotice("名帖已递入试院");
    });
  const resume = () => {
    setActivityOpen(true);
    setSettlement(activityRun?.status === "settled");
  };
  const finish = () =>
    void run(async () => {
      if (!game || !activityRun) return;
      const updated = await api.finishActivity(game.id, activityRun.id);
      setGame(updated);
      setActivityOpen(false);
      setSettlement(false);
      if (updated.event) setPanel("event");
    });
  const warning = (
    <div className="error-banner" role="alert">
      {error}
      <button aria-label="关闭提示" onClick={() => setError("")}>
        ×
      </button>
    </div>
  );
  const settingsButton = (
    <button
      className="gear-button"
      aria-label="游戏设置"
      onClick={() => show("display")}
    >
      ⚙ <span>设置</span>
    </button>
  );
  return (
    <div className={"game-app " + (game && !opening ? "in-world" : "")}>
      {!game ? (
        <div
          className="title-screen"
          style={{ backgroundImage: "url(" + gameDesign.titleBackground + ")" }}
        >
          <div className="title-vignette" />
          <header className="title-top">
            <span className="small-seal">{gameDesign.titleSeal}</span>
            <span>
              {gameDesign.era}
              {gameDesign.startYear}年 · 天下尚安
            </span>
            {settingsButton}
          </header>
          <main className="title-content">
            <p className="eyebrow">{gameDesign.titleEyebrow}</p>
            <h1>{gameDesign.title}</h1>
            <div className="title-rule" />
            <p className="title-poem">{gameDesign.tagline}</p>
            <p className="title-description">
              {gameDesign.titleDialogue.map((line) => (
                <span key={line}>
                  {line}
                  <br />
                </span>
              ))}
            </p>
            <div className="title-actions">
              <button
                className="gold-button"
                disabled={busy || !data}
                onClick={() => show("new")}
              >
                初入此世 <span>→</span>
              </button>
              {!!data?.saves.length && (
                <button
                  className="outline-button"
                  disabled={busy}
                  onClick={() => enter(data.activeId || data.saves[0].id)}
                >
                  续写前尘
                </button>
              )}
              <div>
                <button disabled={!data} onClick={() => show("saves")}>
                  读取存档
                </button>
                <i>·</i>
                <button disabled={!data} onClick={() => show("library")}>
                  整理书卷
                </button>
              </div>
            </div>
            {!data && !error && <p role="status">正在展开山河卷……</p>}
            {error && !panel && warning}
          </main>
          <footer className="title-footer">
            <span>{gameDesign.volume}</span>
            <span>{gameDesign.footer}</span>
          </footer>
        </div>
      ) : opening ? (
        <>
          <div className="gate-settings">{settingsButton}</div>
          <ChapterGate
            key={chapter!.id}
            game={game}
            busy={busy}
            enter={() =>
              void run(async () => {
                setGame(await api.acknowledgeChapter(game.id, chapter!.id));
              })
            }
          />
          {error && !panel && <div className="gate-error">{warning}</div>}
        </>
      ) : (
        <>
          <header className="world-header">
            <button className="wordmark" onClick={() => show("saves")}>
              <span className="small-seal">题</span>
              {gameDesign.title}
            </button>
            <div className="world-date">
              {date!.label}{date!.time}
            </div>
            <div className="world-status">
              <i />
              {busy ? "落墨中…" : "已存档"}
              {settingsButton}
              <button
                onClick={() => {
                  setGame(null);
                  setError("");
                  void sync();
                }}
              >
                离席
              </button>
            </div>
          </header>
          <div className="world-layout">
            <aside className="character-column">
              <section className="character-sheet framed">
                <div className="panel-label">
                  人物志<span>无品无阶</span>
                </div>
                <div className="portrait-stage">
                  <span className="vertical-motto">一介布衣 · 心有山河</span>
                  <Portrait female={game.player.gender === "女"} />
                  <span className="portrait-caption">青溪客</span>
                </div>
                <h1>{game.player.name}</h1>
                <div className="identity-badge">{game.player.title}</div>
                <p className="origin-line">{game.player.origin}</p>
                <dl className="attributes">
                  <div>
                    <dt>学识</dt>
                    <dd>{game.player.knowledge}</dd>
                  </div>
                  <div>
                    <dt>声望</dt>
                    <dd>{game.player.reputation}</dd>
                  </div>
                  <div>
                    <dt>{adventureDesign.currency.name}</dt>
                    <dd>
                      {game.player.coins}
                      <small> {adventureDesign.currency.unit}</small>
                    </dd>
                  </div>
                  <div>
                    <dt>功名</dt>
                    <dd className="subdued">
                      {game.adventure!.exams["county-exam"]?.status === "passed"
                        ? "县试取中"
                        : "尚未取中"}
                    </dd>
                  </div>
                </dl>
                <div className="player-talents">
                  {adventureDesign.attributes.map((a) => (
                    <span key={a.id}>
                      {a.name}
                      <b>{effectiveAttribute(game, a.id)}</b>
                    </span>
                  ))}
                </div>
                <div className="mini-road">
                  {gameDesign.road.map((label, index) => (
                    <span key={label} className={index === 0 ? "current" : ""}>
                      {label}
                    </span>
                  ))}
                </div>
              </section>
            </aside>
            <main className="story-column">
              <div
                className="chapter-heading"
                style={{
                  backgroundImage:
                    "linear-gradient(90deg,#172925e8,#172925bd),url(" +
                    location!.background +
                    ")",
                  backgroundPosition: location!.position,
                }}
              >
                <div>
                  <small>
                    第 {String(game.chapter + 1).padStart(2, "0")} 章 ·{" "}
                    {chapter!.title}
                  </small>
                  <b>
                    {activityOpen
                      ? activityRun?.definition.name
                      : "今日行止，由你落笔"}
                  </b>
                </div>
                <button onClick={() => show("map")}>{location!.name} ↗</button>
              </div>
              {activityOpen && activityRun && attempt ? (
                <>
                  {settlement && activityRun.status === "settled" ? (
                    <Outcome game={game} busy={busy} finish={finish} />
                  ) : (
                    <>
                      <div className="challenge-toolbar">
                        <span>
                          {activityRun.definition.name} ·{" "}
                          {Math.min(
                            activityRun.answered + (attempt.result ? 0 : 1),
                            activityRun.definition.rounds,
                          )}{" "}
                          / {activityRun.definition.rounds} 页
                        </span>
                        <div>
                          <button onClick={() => setActivityOpen(false)}>
                            暂回世界
                          </button>
                          <button
                            disabled={busy}
                            onClick={() => {
                              if (
                                window.confirm(
                                  "放下本轮？已答课业保留，未完成时不发整轮奖励。",
                                )
                              )
                                void run(async () => {
                                  setGame(
                                    await api.abandonActivity(
                                      game.id,
                                      activityRun.id,
                                    ),
                                  );
                                  setActivityOpen(false);
                                });
                            }}
                          >
                            放下本轮
                          </button>
                        </div>
                      </div>
                      <QuestionPanel
                        key={attempt.id}
                        attempt={attempt}
                        busy={busy}
                        submit={(answer) =>
                          void run(async () => {
                            setGame(
                              await api.answer(game.id, {
                                attemptId: attempt.id,
                                answer,
                              }),
                            );
                          })
                        }
                        next={() => {
                          if (activityRun.status === "settled")
                            setSettlement(true);
                          else
                            void run(async () => {
                              setGame(await api.next(game.id, attempt.id));
                            });
                        }}
                        nextLabel={
                          activityRun.status === "settled"
                            ? "查看此行战果 →"
                            : "展开下一页 →"
                        }
                        allowReview={false}
                        note={game.notes[attempt.question.id] || ""}
                        showNote={() => show("notes")}
                        eventPending={false}
                        reviewOnly={reviewOnly}
                        setReview={setReview}
                        onEvent={() => show("event")}
                      />
                    </>
                  )}
                </>
              ) : (
                <WorldHub
                  key={location!.id}
                  game={game}
                  inspect={inspect}
                  people={meet}
                  map={() => show("map")}
                  study={() => show("study")}
                  bag={() => show("bag")}
                  resume={resume}
                  legacy={() => show("event")}
                />
              )}
            </main>
            <aside className="affairs-column">
              <WorldGoals game={game} inspect={inspect} openExam={() => show("exam")} />
              <section className="life-journal-panel framed">
                <div className="panel-label">
                  人生札记<span>案头</span>
                </div>
                <button
                  className="journal-today-button"
                  onClick={() => show("journal")}
                >
                  <small>今日札记</small>
                  <b>{game.journal.at(-1)?.title || "今日尚无新记"}</b>
                  <span>
                    {game.journal.at(-1)?.text ||
                      "今日尚未留下新的记录，之后的行程会写在这里。"}
                  </span>
                </button>
                <button
                  className="journal-history-button"
                  onClick={() => show("journal")}
                >
                  <span>
                    <b>历史札记</b>
                    <small>已收录 {game.journal.length} 篇</small>
                  </span>
                  <strong>查看全部</strong>
                </button>
              </section>
            </aside>
          </div>
          <nav className="game-dock" aria-label="行旅案头">
            {dock.map((item) => (
              <button key={item.id} onClick={() => show(item.id)}>
                <span>{item.icon}</span>
                <b>{item.label}</b>
                {item.id === "review" &&
                  Object.values(game.learning).some(
                    (r) => r.wrong > 0 && !isLearningMastered(r),
                  ) && (
                    <i />
                  )}
              </button>
            ))}
          </nav>
          {error && !panel && <div className="floating-error">{warning}</div>}
        </>
      )}
      {notice && !panel && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
      {panel && (data || panel === "display") && (
        <Modal
          title={panelNames[panel]}
          close={() => {
            if (!busy) {
              setPanel(null);
              if (game?.adventure?.encounter)
                void run(async () =>
                  setGame(await api.dismissEncounter(game.id)),
                );
            }
          }}
          wide={panel === "library" || panel === "stats"}
          subtitle={
            panel === "new"
              ? "前尘未定 · 一念入世"
              : (game?.player.name || "青溪") + " · 案头文书"
          }
        >
          {error && warning}
          {notice && (
            <p className="saved-notice" role="status">
              {notice}
            </p>
          )}
          {panel === "display" && <DisplaySettings />}
          {panel === "new" && data && (
            <NewGameForm banks={data.banks} busy={busy} start={start} />
          )}
          {panel === "library" && data && (
            <Library
              banks={data.banks}
              busy={busy}
              save={changeBank}
              remove={deleteBank}
              report={reportTemporary}
            />
          )}
          {panel === "saves" && data && (
            <>
              <div className="toolbar">
                <button
                  className="gold-button"
                  disabled={busy}
                  onClick={() => show("new")}
                >
                  新的一生
                </button>
                <label className="file-button">
                  导入存档
                  <input
                    type="file"
                    accept=".json"
                    disabled={busy}
                    onChange={(e) => {
                      const file = e.target.files?.[0];
                      if (file)
                        void run(async () => {
                          if (file.size > 20_000_000)
                            throw new Error("文件超过 20 MB");
                          setGame(await api.importSave(await file.text()));
                          setActivityOpen(false);
                          setSettlement(false);
                          await sync();
                          setPanel(null);
                          setNotice("已作为新的存档恢复");
                        });
                    }}
                  />
                </label>
              </div>
              {!data.saves.length && (
                <div className="empty-state">
                  <span>牍</span>
                  <h3>前尘尚未落笔</h3>
                  <p>开始新的一生，存档会自动记录际遇。</p>
                </div>
              )}
              {data.saves.slice(savePage * 3, savePage * 3 + 3).map((save) => (
                <article className="save-row" key={save.id}>
                  <div>
                    <h3>
                      {save.name}
                      <small>{save.title}</small>
                    </h3>
                    <p>
                      已修习 {save.total} 题 ·{" "}
                      {new Date(save.updatedAt).toLocaleString()}
                    </p>
                  </div>
                  <div>
                    <button disabled={busy} onClick={() => enter(save.id)}>
                      续写
                    </button>
                    <button
                      disabled={busy}
                      onClick={() =>
                        void run(async () => {
                          download(
                            save.name + "-存档.json",
                            await api.exportSave(save.id),
                          );
                          setNotice("存档已导出");
                        })
                      }
                    >
                      导出
                    </button>
                    <button
                      disabled={busy}
                      className="danger"
                      onClick={() => {
                        if (
                          window.confirm(
                            "删除“" + save.name + "”这份存档？建议先导出备份。",
                          )
                        )
                          void run(async () => {
                            await api.deleteGame(save.id);
                            if (game?.id === save.id) setGame(null);
                            setSavePage(0);
                            await sync();
                          });
                      }}
                    >
                      删除
                    </button>
                  </div>
                </article>
              ))}
              {data.saves.length > 3 && (
                <div className="pager">
                  <button
                    disabled={!savePage}
                    onClick={() => setSavePage((p) => p - 1)}
                  >
                    上一页
                  </button>
                  <span>
                    {savePage + 1}/{Math.ceil(data.saves.length / 3)}
                  </span>
                  <button
                    disabled={(savePage + 1) * 3 >= data.saves.length}
                    onClick={() => setSavePage((p) => p + 1)}
                  >
                    下一页
                  </button>
                </div>
              )}
              <p className="hint">
                保存在当前浏览器，请定期导出备份。更换浏览器或清理网站数据不会自动迁移存档。
              </p>
            </>
          )}
          {game && panel === "journal" && <Journal game={game} />}
          {game && panel === "people" && (
            <NpcPanel key={npcFocus||"nearby"} initialNpc={npcFocus}
              game={game}
              busy={busy}
              inspect={inspect}
              travel={travel}
              talk={(npc, topic) =>
                void run(async () =>
                  setGame(await api.talk(game.id, npc, topic)),
                )
              }
              claim={(npc, index) =>
                void run(async () =>
                  setGame(await api.claimBond(game.id, npc, index)),
                )
              }
            />
          )}
          {game && panel === "study" && (
            <ActivityShelf game={game} inspect={inspect} />
          )}
          {game && panel === "exam" && (
            <ExamPanel
              game={game}
              busy={busy}
              inspect={inspect}
              travel={travel}
              register={register}
              report={reportTemporary}
            />
          )}
          {game && panel === "activity" && (
            <ActivityDetail
              game={game}
              id={detailId}
              busy={busy}
              start={begin}
              travel={travel}
              study={() => show("study")}
            />
          )}
          {game && panel === "bag" && (
            <Inventory
              game={game}
              busy={busy}
              use={(id) =>
                void run(async () => setGame(await api.useItem(game.id, id)))
              }
              buy={(id) =>
                void run(async () => setGame(await api.buyItem(game.id, id)))
              }
            />
          )}
          {game && panel === "map" && (
            <WorldMap
              game={game}
              busy={busy}
              travel={travel}
              study={() => show("study")}
            />
          )}
          {game && panel === "review" && (
            <Reviews
              game={game}
              busy={busy}
              start={() => {
                inspect("review");
              }}
            />
          )}
          {game && panel === "stats" && <Statistics game={game} />}
          {game && attempt && panel === "story" && (
            <div className="story-recap">
              <Portrait variant={attempt.scene.npcId} />
              <div>
                <small>{attempt.scene.location}</small>
                <h3>{attempt.scene.speaker}</h3>
                <p>{attempt.scene.text}</p>
                <blockquote>{attempt.scene.dialogue}</blockquote>
                {attempt.result && <p>{attempt.result.story}</p>}
              </div>
            </div>
          )}
          {game?.event && panel === "event" && (
            <div className="event-conversation">
              <small>偶遇 · 你的一念</small>
              <h3>{game.event.title}</h3>
              <p>{game.event.text}</p>
              {game.event.options.map((option) => (
                <button
                  key={option.id}
                  disabled={busy}
                  onClick={() =>
                    void run(async () => {
                      const updated = await api.choose(
                        game.id,
                        game.event!.id,
                        option.id,
                      );
                      setGame(updated);
                      setPanel(null);
                      setNotice(option.hint);
                    })
                  }
                >
                  {option.text}
                  <small>{option.hint}</small>
                  <span>→</span>
                </button>
              ))}
            </div>
          )}
          {game && attempt && panel === "notes" && (
            <>
              <p className="hint">{attempt.question.question}</p>
              <textarea
                aria-label="卷边批注"
                maxLength={gameDesign.limits.noteLength}
                rows={5}
                value={note}
                onChange={(e) => setNote(e.target.value)}
                placeholder="记下自己的理解，或一条提醒……"
              />
              <button
                className="gold-button"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    setGame(
                      await api.saveNote(game.id, attempt.question.id, note),
                    );
                    setPanel(null);
                    setNotice("批注已夹入卷中");
                  })
                }
              >
                夹入卷中
              </button>
            </>
          )}
        </Modal>
      )}
    </div>
  );
}
export default App;

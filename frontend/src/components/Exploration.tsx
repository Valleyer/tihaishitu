/**
 * 世界默认页与活动入口。这里不自动发题：玩家先看人物、目的地和奖励，再决定行动。
 * 活动、评分档、奖励、人物话题均取配置；没有把具体关卡写死在按钮事件里。
 */
import { useState } from "react";
import type { Game } from "../domain/types";
import {
  activities,
  companions,
  exams,
  favorabilityLevel,
  items,
  mapDesign,
} from "../content";
import {
  activityIssues,
  rewardLines,
  requirementIssues,
} from "../engine/AdventureEngine";
import { Portrait } from "./Portrait";

export function WorldHub({
  game,
  inspect,
  people,
  map,
  study,
  bag,
  resume,
  legacy,
}: {
  game: Game;
  inspect: (id: string) => void;
  people: (id?:string) => void;
  map: () => void;
  study: () => void;
  bag: () => void;
  resume: () => void;
  legacy: () => void;
}) {
  const state = game.adventure!,
    location =
      mapDesign.locations.find((l) => l.id === state.locationId) ||
      mapDesign.locations[0];
  // 科举正试由“县试”面板管理，避免像普通副本一样在街头随手点开。
  const examActivityIds = new Set(
    exams.flatMap((e) => [e.activityId, e.preparationActivityId]),
  );
  const local = activities.filter(
    (a) =>
      a.locationId === location.id &&
      a.kind !== "companion" &&
      !examActivityIds.has(a.id) &&
      !(a.activityMode === "task" && (state.clears[a.id] || 0) > 0),
  );
  const [page, setPage] = useState(0),
    current = Math.min(page, Math.max(0, Math.ceil(local.length / 2) - 1));
  return (
    <section className="exploration-hub">
      <div
        className="world-scene"
        style={{
          backgroundImage:
            "linear-gradient(0deg,#0c211df5,transparent 90%),url(" +
            location.background +
            ")",
          backgroundPosition: location.position,
        }}
      >
        <div className="scene-heading">
          <h1>{location.name.split(" · ").at(-1)}</h1>
          <p>{location.ambience}</p>
        </div>
        <button className="scene-map-button" onClick={map}>
          展开地图 ↗
        </button>
        <div className="resident-row">
          {location.npcs.map((id) => {
            const npc = game.npcs.find((n) => n.id === id);
            return (
              npc && (
                <button key={id} onClick={()=>people(id)}>
                  <span className="resident-face">
                    <Portrait variant={id} />
                  </span>
                  <span>
                    {npc.name}
                    <small>过去说说话 →</small>
                  </span>
                </button>
              )
            );
          })}
        </div>
      </div>
      <div className="world-choices">
        <div className="world-section-title">
          <span>此间可遇</span>
          <small>有所求，才有所往</small>
          <button onClick={bag}>看看行囊 →</button>
        </div>
        {state.run ? (
          <div className="resume-banner">
            <div>
              <b>{state.run.definition.name}</b>
              <p>
                {state.run.status === "settled"
                  ? "此行战果已记下，来看看所得。"
                  : "已完成 " +
                    state.run.knowledgePointIndex +
                    "/" +
                    (state.run.plannedRounds ?? state.run.definition.rounds) +
                    " 道正式题，随时可接着走。"}
              </p>
            </div>
            <button className="gold-button" onClick={resume}>
              {state.run.status === "settled" ? "查看战果" : "继续行程"}
            </button>
          </div>
        ) : (
          <div className="opportunity-grid">
            {local.slice(current * 2, current * 2 + 2).map((activity) => {
              const issues = activityIssues(game, activity);
              return (
                <button
                  className="opportunity"
                  key={activity.id}
                  onClick={() => inspect(activity.id)}
                >
                  <small>
                    {activity.quest === "main"
                      ? "✦ 主线任务"
                      : activity.kind === "story"
                        ? "✦ 支线任务"
                        : "◇ 挑战副本"}
                  </small>
                  <h3>{activity.name}</h3>
                  <p>{activity.invitation}</p>
                  <span>
                    {issues.length
                      ? issues[0]
                      : activity.kind === "dungeon"
                        ? "寻一件只属于此地的宝物"
                        : "听听事情的来龙去脉"}{" "}
                    →
                  </span>
                </button>
              );
            })}
            {!local.length && (
              <div className="quiet-place">
                <h3>今夜无事</h3>
                <p>正好静读一卷，或去访一位故人。</p>
                <button onClick={study}>点灯读书</button>
              </div>
            )}
          </div>
        )}
        {local.length > 2 && !state.run && (
          <div className="pager">
            <button disabled={!current} onClick={() => setPage(current - 1)}>
              前页
            </button>
            <span>
              {current + 1}/{Math.ceil(local.length / 2)}
            </span>
            <button
              disabled={(current + 1) * 2 >= local.length}
              onClick={() => setPage(current + 1)}
            >
              后页
            </button>
          </div>
        )}
        {game.event && (
          <button className="legacy-encounter" onClick={legacy}>
            故事尚未说完：{game.event.title} →
          </button>
        )}
      </div>
    </section>
  );
}

export function ActivityShelf({
  inspect,
}: {
  inspect: (id: string) => void;
}) {
  return (
    <>
      <p className="hint">
        潜心读书积累学识，抄书谋生赚取银两。两项日常均为五题，60 分通关。
      </p>
      <div className="activity-shelf">
        {activities
          .filter((a) => ["read", "copy-work"].includes(a.id))
          .map((a) => (
            <button key={a.id} onClick={() => inspect(a.id)}>
              <small>{a.subtitle}</small>
              <h3>{a.name}</h3>
              <p>{a.description}</p>
              <span>{rewardLines(a.tiers.at(-1)!.rewards).join(" · ")}</span>
            </button>
          ))}
      </div>
    </>
  );
}
export function ActivityDetail({
  game,
  id,
  busy,
  start,
  travel,
  study,
}: {
  game: Game;
  id: string;
  busy: boolean;
  start: () => void;
  travel: (id: string) => void;
  study: () => void;
}) {
  const activity = activities.find((a) => a.id === id);
  if (!activity) return <p>这段行程的配置已移除。</p>;
  const issues = activityIssues(game, activity),
    state = game.adventure!,
    elsewhere = activity.locationId && state.locationId !== activity.locationId;
  const person = game.npcs.find((n) => n.id === activity.npcId);
  return (
    <div className="activity-detail">
      <h2>{activity.name}</h2>
      <p>{activity.description}</p>
      <div className="invitation">
        {person && (
          <span className="invitation-face">
            <Portrait variant={person.id} />
          </span>
        )}
        <blockquote>
          {person?.name && <b>{person.name}</b>}
          {activity.invitation}
        </blockquote>
      </div>
      <div className="reward-tiers">
        {activity.tiers.filter((t) => t.minScore >= 60).map((t) => (
          <div key={t.minScore}>
            <strong>通关奖励</strong>
            <small>达到{t.minScore}分</small>
            <span>
              {rewardLines(t.rewards).join(" · ") || "保留本轮学识与错题记录"}
              {t.firstRewards && rewardLines(t.firstRewards).length > 0 && (
                <em>
                  {state.rewardClaims.includes(activity.id + ":" + t.minScore)
                    ? "首次奖励已获得"
                    : "首次达到另得"}
                  ：{rewardLines(t.firstRewards).join(" · ")}
                </em>
              )}
            </span>
          </div>
        ))}
      </div>
      {issues.length > 0 && (
        <div className="gate-reasons">
          尚需：{issues.join("；")}
          <button onClick={study}>先去读书提升 →</button>
        </div>
      )}
      {state.run && (
        <p className="hint">当前还有一段行程，请先回世界继续或结束。</p>
      )}
      <button
        className="gold-button full"
        disabled={busy || issues.length > 0 || !!state.run}
        onClick={() => (elsewhere ? travel(activity.locationId!) : start())}
      >
        {elsewhere
          ? "先前往 " +
            mapDesign.locations.find((l) => l.id === activity.locationId)?.name
          : activity.kind === "study"
            ? "点灯，展开这一卷"
            : activity.kind === "work"
              ? "提笔，开始校抄"
              : activity.kind === "companion"
                ? "坐下，与他一道读"
                : activity.kind === "exam"
                  ? "点名入号，开始应试"
                  : "应下此事，进入挑战"}{" "}
        →
      </button>
    </div>
  );
}

/** 县试是世界进阶入口：先看资格，再报名，最后才进入答题。 */
export function ExamPanel({
  game,
  busy,
  inspect,
  travel,
  register,
  report,
}: {
  game: Game;
  busy: boolean;
  inspect: (id: string) => void;
  travel: (id: string) => void;
  register: (id: string) => void;
  report: (message: string) => void;
}) {
  const exam =
      exams.find((item) => game.adventure!.exams[item.id]?.status !== "passed") ||
      exams.at(-1)!,
    record = game.adventure!.exams[exam.id],
    issues = requirementIssues(game, exam.requirements),
    atExam = game.adventure!.locationId === exam.locationId,
    shortOfMoney = game.player.coins < exam.fee,
    missing = issues;
  const statusName = {
    unregistered: "尚未报名",
    registered: "候场应试",
    preparing: "候场重试",
    passed: "红榜取中",
  }[record.status];
  const action = () => {
    if (record.status === "passed") return;
    else if (record.status === "preparing") inspect(exam.activityId);
    else if (record.status === "registered") inspect(exam.activityId);
    else if (missing.length) {
      // 报名差距在点击查验时集中提示，不把一长串数值常驻在面板上。
      report("报名条件尚未满足：" + missing.join("；"));
    } else if (!atExam) travel(exam.locationId);
    else register(exam.id);
  };
  const actionText =
    record.status === "passed"
      ? "本场已取中"
      : record.status === "preparing"
        ? "再次入号，应试十题"
        : record.status === "registered"
          ? "点名入号，应试十题"
          : missing.length
            ? "查验报名条件"
            : !atExam
              ? "前往试院报名"
              : "验明资格，递帖报名";
  return (
    <div className="exam-panel">
      <header>
        <small>{exam.subtitle}</small>
        <h2>{exam.name}</h2>
        <p>{exam.description}</p>
      </header>
      <div className={"exam-status " + record.status}>
        <span>榜</span>
        <div>
          <small>当前进度</small>
          <h3>{statusName}</h3>
          <p>{exam.dialogues[record.status]}</p>
        </div>
      </div>
      <div className="exam-steps">
        <article className={record.status !== "unregistered" ? "done" : ""}>
          <b>壹 · 验明资格</b>
          <p>{issues.length ? "报名资格尚未齐备" : "学识与声名均已验明"}</p>
        </article>
        <article className={record.status !== "unregistered" ? "done" : ""}>
          <b>贰 · 递帖报名</b>
          <p>{shortOfMoney ? "报名银尚未备足" : "报名银 " + exam.fee + " 两"}</p>
        </article>
        <article className={record.status === "passed" ? "done" : ""}>
          <b>叁 · 十题定榜</b>
          <p>十题全对取中</p>
        </article>
      </div>
      <button className="gold-button full" disabled={busy || !!game.adventure!.run || record.status === "passed"} onClick={action}>
        {actionText} →
      </button>
      <p className="hint">报名只确认资格；开考时暂收报名银。未能全对或中途退出会原数退回，可重新开始；取中后任务永久结案。</p>
    </div>
  );
}

export function NpcPanel({
  initialNpc,
  game,
  busy,
  inspect,
  travel,
  talk,
  claim,
}: {
  game: Game;
  busy: boolean;
  inspect: (id: string) => void;
  travel: (id: string) => void;
  initialNpc?:string;
  talk: (id: string, topic: string) => void;
  claim: (id: string, index: number) => void;
}) {
  const [selected, setSelected] = useState(
    initialNpc ||
    companions.find((c) => c.locationId === game.adventure!.locationId)
      ?.npcId || companions[0].npcId,
  );
  const [topicId, setTopic] = useState(""),
    [line, setLine] = useState(0);
  const companion = companions.find((c) => c.npcId === selected)!,
    npc = game.npcs.find((n) => n.id === selected)!;
  const here = game.adventure!.locationId === companion.locationId;
  const topic = companion.topics.find((t) => t.id === topicId),
    greeting = [...companion.greetings]
      .reverse()
      .find((g) => npc.favorability >= g.minFavorability)?.text;
  return (
    <div className="companion-panel">
      <div className="companion-tabs">
        {companions.map((c) => (
          <button
            className={selected === c.npcId ? "selected" : ""}
            key={c.npcId}
            onClick={() => {
              setSelected(c.npcId);
              setTopic("");
              setLine(0);
            }}
          >
            <span className="companion-tab-face">
              <Portrait variant={c.npcId} />
            </span>
            <span>
              <b>{game.npcs.find((n) => n.id === c.npcId)?.name}</b>
              <small>
                {c.locationId === game.adventure!.locationId
                  ? "此刻在此"
                  : "异地可访"}
              </small>
            </span>
          </button>
        ))}
      </div>
      <div className="companion-stage">
        <div className="companion-portrait">
          <Portrait variant={npc.id} />
          <span>{npc.name}</span>
        </div>
        <div className="companion-copy">
          <small>{npc.role}</small>
          <h2>{npc.name}</h2>
          <p>{companion.personality}</p>
          <div className="bond-meter">
            <span>好感度 {npc.favorability}</span>
            <meter min={0} max={100} value={npc.favorability} />
            <small>{favorabilityLevel(npc.favorability)}</small>
          </div>
          <div className="dialogue-box">
            <small>
              {topic
                ? "正在聊 · " + (line + 1) + "/" + topic.lines.length
                : "此刻问候"}
            </small>
            <blockquote key={(topic?.id || "greeting") + line} aria-live="polite">
              {topic ? topic.lines[line] : greeting}
            </blockquote>
            {topic && line < topic.lines.length - 1 ? (
              <button
                className="dialogue-continue"
                onClick={() => setLine((current) => current + 1)}
              >
                继续听他说 · 下一句 {line + 2}/{topic.lines.length} →
              </button>
            ) : topic ? (
              <span className="dialogue-end">此话题已说完 · 可选择下方其他话题</span>
            ) : null}
          </div>
        </div>
      </div>
      {!here ? (
        <div className="visit-reminder">
          <p>
            他在{" "}
            {
              mapDesign.locations.find((l) => l.id === companion.locationId)
                ?.name
            }
            ，当面说话才有意思。
          </p>
          <button
            disabled={busy || !!game.adventure!.run}
            onClick={() => travel(companion.locationId)}
          >
            前去拜访 →
          </button>
        </div>
      ) : (
        <div className="conversation-options">
          {companion.topics.map((t) => (
            <button
              disabled={busy || npc.favorability < t.minFavorability}
              key={t.id}
              onClick={() => {
                setTopic(t.id);
                setLine(0);
                talk(npc.id, t.id);
              }}
            >
              {t.label}
              <small>
                {npc.favorability < t.minFavorability
                  ? "好感度 " + t.minFavorability + " 解锁"
                  : "聊一聊"}
              </small>
            </button>
          ))}
          {companion.activities.map((id) => (
            <button
              className="gold-button"
              key={id}
              onClick={() => inspect(id)}
            >
              邀他共读 · {activities.find((a) => a.id === id)?.name} →
            </button>
          ))}
        </div>
      )}
      <div className="bond-gifts">
        {companion.milestones.map((m, index) => {
          const got = game.adventure!.rewardClaims.includes(
            "bond:" + npc.id + ":" + index,
          );
          return (
            <article key={index}>
              <div>
                <b>{m.title}</b>
                <small>
                  好感度 {m.favorability} · {rewardLines(m.reward).join(" · ")}
                </small>
                {got && <p>{m.dialogue}</p>}
              </div>
              <button
                disabled={busy || got || !here || npc.favorability < m.favorability}
                onClick={() => claim(npc.id, index)}
              >
                {got ? "已珍藏" : "领取心意"}
              </button>
            </article>
          );
        })}
      </div>
    </div>
  );
}
export function Inventory({
  game,
  busy,
  use,
  buy,
}: {
  game: Game;
  busy: boolean;
  use: (id: string) => void;
  buy: (id: string) => void;
}) {
  const [shop, setShop] = useState(false),
    [page, setPage] = useState(0);
  const state = game.adventure!,
    shopAvailable = items.some((item) => (item.price || 0) > 0),
    list = items.filter((i) =>
      shop && shopAvailable ? !!i.price : (state.inventory[i.id] || 0) > 0,
    ),
    pages = Math.max(1, Math.ceil(list.length / 4)),
    current = Math.min(page, pages - 1);
  return (
    <>
      <div className="inventory-top">
        <div>
          <b>随身珍藏</b>
          <span>银两 {game.player.coins} 两</span>
        </div>
        {shopAvailable && (
          <button
            onClick={() => {
              setShop(!shop);
              setPage(0);
            }}
          >
            {shop ? "返回行囊" : "去纸墨铺添置 →"}
          </button>
        )}
      </div>
      <div className="inventory-grid">
        {list.slice(current * 4, current * 4 + 4).map((item) => {
          const equipped = Object.values(state.equipped).includes(item.id);
          return (
            <article className="item-card" key={item.id}>
              <span className="item-symbol">{item.symbol}</span>
              <div>
                <small>
                  {item.rarity}
                  {equipped ? " · 已装备" : ""}
                </small>
                <h3>
                  {item.name}
                  {!shop && <small> ×{state.inventory[item.id]}</small>}
                </h3>
                <p>{item.description}</p>
                {shop ? (
                  <button
                    disabled={busy || game.player.coins < (item.price || 0)}
                    onClick={() => buy(item.id)}
                  >
                    花 {item.price} 两添置
                  </button>
                ) : item.kind !== "keepsake" ? (
                  <button disabled={busy} onClick={() => use(item.id)}>
                    {item.kind === "consumable"
                      ? "使用一份"
                      : equipped
                        ? "卸下"
                        : "装备"}
                  </button>
                ) : (
                  <small>信物已入藏</small>
                )}
              </div>
            </article>
          );
        })}
      </div>
      {!list.length && (
        <div className="empty-state">
          <span>囊</span>
          <h3>行囊尚轻</h3>
          <p>试炼的高评奖励与故人的心意，会慢慢填满这里。</p>
        </div>
      )}
      {pages > 1 && (
        <div className="pager">
          <button disabled={!current} onClick={() => setPage(current - 1)}>
            上一页
          </button>
          <span>
            {current + 1}/{pages}
          </span>
          <button
            disabled={current === pages - 1}
            onClick={() => setPage(current + 1)}
          >
            下一页
          </button>
        </div>
      )}
      <p className="hint">
        行囊保存旅途信物与剧情凭证；它们不再提供成长数值加成。
      </p>
    </>
  );
}
export function Outcome({
  game,
  busy,
  finish,
}: {
  game: Game;
  busy: boolean;
  finish: () => void;
}) {
  const run = game.adventure!.run!;
  return (
    <section className="activity-outcome">
      <small>此行已毕 · {run.definition.name}</small>
      <div className="outcome-score">
        {run.score}
      </div>
      <h2>
        {run.definition.kind === "exam"
          ? run.score >= run.definition.passScore
            ? "红榜有名，功名初成"
            : "本次尚未取中，补强后再来"
          : run.score >= run.definition.passScore
          ? "有所获，亦有所长"
          : "错处已明，随时可以再试"}
      </h2>
      <blockquote>{run.response}</blockquote>
      {run.rewards.length > 0 && (
        <div className="loot-list">
          {run.rewards.map((line, i) => <span key={i}>{line}</span>)}
        </div>
      )}
      <button className="gold-button" disabled={busy} onClick={finish}>
        回到青溪
      </button>
    </section>
  );
}

/** 侧栏展示可追求的奖励，代替持续催促累计题数。 */
export function WorldGoals({
  game,
  inspect,
  openExam,
}: {
  game: Game;
  inspect: (id: string) => void;
  openExam: () => void;
}) {
  const exam =
      exams.find((item) => game.adventure!.exams[item.id]?.status !== "passed") ||
      exams.at(-1)!,
    record = game.adventure!.exams[exam.id],
    allPassed = exams.every(
      (item) => game.adventure!.exams[item.id]?.status === "passed",
    );
  const target = activities
    .filter((a) => a.kind === "dungeon")
    .find((a) =>
      a.tiers.some((t) =>
        Object.keys(t.firstRewards?.items || {}).some(
          (id) => !game.adventure!.inventory[id],
        ),
      ),
    );
  const treasure = target?.tiers
    .flatMap((t) => Object.keys(t.firstRewards?.items || {}))
    .map((id) => items.find((i) => i.id === id))
    .find(Boolean);
  return (
    <section className="objective-panel framed">
      <div className="panel-label">
        {!allPassed ? "主线任务" : "支线任务"}
        <span>{!allPassed ? "科举" : "游历"}</span>
      </div>
      {!allPassed ? (
        <>
          <h2>
            {record.status === "unregistered"
              ? "报名" + exam.name
              : exam.name + "入号应试"}
          </h2>
          <p>{exam.dialogues[record.status]}</p>
          <button className="main-quest-button" onClick={openExam}>
            查看{exam.name.replace("青溪", "").replace("临川", "")}进度
          </button>
        </>
      ) : target && treasure ? (
        <>
          <h2>{treasure.name}</h2>
          <p>{treasure.description}</p>
          <div className="goal-treasure">{treasure.symbol}</div>
          <p>{target.name} · {target.tiers.find(t=>t.firstRewards?.items?.[treasure.id])?.minScore} 分起可得</p>
          <button className="text-button" onClick={() => inspect(target.id)}>
            看看如何取得 →
          </button>
        </>
      ) : (
        <>
          <h2>青溪珍藏已齐</h2>
          <p>还可以与故人共读，收下他们的心意，或重返旧地刷新最高成绩。</p>
        </>
      )}
      <div className="world-collection">
        <span>
          已访 {game.adventure!.visited.length} / {mapDesign.locations.length}{" "}
          地
        </span>
        <span>
          珍藏{" "}
          {Object.values(game.adventure!.inventory).filter((n) => n > 0).length}{" "}
          种
        </span>
      </div>
    </section>
  );
}

/**
 * 世界默认页与活动入口。这里不自动发题：玩家先看人物、目的地和奖励，再决定行动。
 * 活动、评分档、奖励、人物话题均取配置；没有把具体关卡写死在按钮事件里。
 */
import { useState } from "react";
import type { Game } from "../domain/types";
import {
  activities,
  adventureDesign,
  companions,
  exams,
  items,
  mapDesign,
} from "../content";
import {
  activityIssues,
  attributeName,
  effectiveAttribute,
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
      !examActivityIds.has(a.id),
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
          <small>{adventureDesign.hub.eyebrow}</small>
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
                  : "已经历 " +
                    state.run.answered +
                    "/" +
                    state.run.definition.rounds +
                    " 页，随时可接着走。"}
              </p>
            </div>
            <button className="gold-button" onClick={resume}>
              {state.run.status === "settled" ? "查看战果" : "继续行程"}
            </button>
          </div>
        ) : (
          <div className="opportunity-grid">
            {local.slice(current * 2, current * 2 + 2).map((activity) => {
              const issues = activityIssues(game, activity),
                done =
                  (state.clears[activity.id] || 0) > 0 && !activity.repeatable;
              return (
                <button
                  className={"opportunity " + (done ? "completed" : "")}
                  key={activity.id}
                  onClick={() => inspect(activity.id)}
                >
                  <small>
                    {activity.quest === "main"
                      ? "✦ 主线任务"
                      : activity.kind === "story"
                        ? "✦ 支线任务"
                        : "◇ 挑战副本"}
                    {done ? " · 已完成" : ""}
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
  game,
  inspect,
}: {
  game: Game;
  inspect: (id: string) => void;
}) {
  return (
    <>
      <p className="hint">
        点一盏灯，选一卷想读的书。每轮五题；学识随作答积累，六十分获得基础奖励，满分获得完美奖励。
      </p>
      <div className="activity-shelf">
        {activities
          .filter((a) => a.kind === "study")
          .map((a) => (
            <button key={a.id} onClick={() => inspect(a.id)}>
              <small>{a.subtitle}</small>
              <h3>{a.name}</h3>
              <p>{a.description}</p>
              <span>
                {rewardLines(a.tiers.at(-1)!.rewards).join(" · ")}
                <br />
                最高成绩 {game.adventure!.best[a.id] ?? "—"} 分 →
              </span>
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
      <small>
        {adventureDesign.rankNames[activity.kind]} · {activity.rounds} 题 ·{" "}
        {activity.passScore} 分达标
      </small>
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
            <strong>
              {t.minScore} 分<small>{t.label}</small>
            </strong>
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
  const exam = exams[0],
    record = game.adventure!.exams[exam.id],
    issues = requirementIssues(game, exam.requirements),
    atExam = game.adventure!.locationId === exam.locationId,
    shortOfMoney = game.player.coins < exam.fee,
    missing = [
      ...issues,
      ...(shortOfMoney
        ? ["银两 " + game.player.coins + "/" + exam.fee]
        : []),
    ];
  const statusName = {
    unregistered: "尚未报名",
    registered: "候场应试",
    preparing: "候场重试",
    passed: "红榜取中",
  }[record.status];
  const action = () => {
    if (record.status === "passed") {
      if (game.adventure!.locationId === "prefecture-road")
        inspect("prefecture-departure");
      else travel("prefecture-road");
    } else if (record.status === "preparing") inspect(exam.activityId);
    else if (record.status === "registered") inspect(exam.activityId);
    else if (missing.length) {
      // 报名差距在点击查验时集中提示，不把一长串数值常驻在面板上。
      report("报名条件尚未满足：" + missing.join("；"));
    } else if (!atExam) travel(exam.locationId);
    else register(exam.id);
  };
  const actionText =
    record.status === "passed"
      ? game.adventure!.locationId === "prefecture-road"
        ? "展开府城新篇"
        : "沿驿路赴府"
      : record.status === "preparing"
        ? "再次入号，应试十题"
        : record.status === "registered"
          ? "点名入号，应试十题"
          : missing.length
            ? "查验报名条件"
            : !atExam
              ? "前往试院报名"
              : "缴银递帖，正式报名";
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
          <p>{issues.length ? "报名资格尚未齐备" : "学识、声名与三门本领均已验明"}</p>
        </article>
        <article className={record.status !== "unregistered" ? "done" : ""}>
          <b>贰 · 递帖报名</b>
          <p>{shortOfMoney ? "报名银尚未备足" : "报名银 " + exam.fee + " 两已经备妥"}</p>
        </article>
        <article className={record.status === "passed" ? "done" : ""}>
          <b>叁 · 十题定榜</b>
          <p>十题全对取中 · 已应试 {record.attempts} 次 · 最高 {record.best || "—"} 分</p>
        </article>
      </div>
      <button className="gold-button full" disabled={busy || !!game.adventure!.run} onClick={action}>
        {actionText} →
      </button>
      <p className="hint">报名不立刻发卷。未能全对只记录错题，不扣奖励、不重复收报名银，可直接再次应试。</p>
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
      .find((g) => npc.affinity >= g.minAffinity)?.text;
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
            <span>好感 {npc.affinity}</span>
            <meter min={0} max={100} value={npc.affinity} />
            <small>信任 {npc.trust}</small>
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
              disabled={busy || npc.affinity < t.minAffinity}
              key={t.id}
              onClick={() => {
                setTopic(t.id);
                setLine(0);
                talk(npc.id, t.id);
              }}
            >
              {t.label}
              <small>
                {npc.affinity < t.minAffinity
                  ? "好感 " + t.minAffinity + " 解锁"
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
                  好感 {m.affinity} · {rewardLines(m.reward).join(" · ")}
                </small>
                {got && <p>{m.dialogue}</p>}
              </div>
              <button
                disabled={busy || got || !here || npc.affinity < m.affinity}
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
    list = items.filter((i) =>
      shop ? !!i.price : (state.inventory[i.id] || 0) > 0,
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
        <button
          onClick={() => {
            setShop(!shop);
            setPage(0);
          }}
        >
          {shop ? "返回行囊" : "去纸墨铺添置 →"}
        </button>
      </div>
      <div className="attribute-strip">
        {adventureDesign.attributes.map((a) => (
          <span key={a.id}>
            {a.name}
            <b>{effectiveAttribute(game, a.id)}</b>
            <small>
              本领 {state.attributes[a.id] || 0} + 装备{" "}
              {effectiveAttribute(game, a.id) - (state.attributes[a.id] || 0)}
            </small>
          </span>
        ))}
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
                {item.bonuses && (
                  <em>
                    {Object.entries(item.bonuses)
                      .map(([id, v]) => attributeName(id) + " +" + v)
                      .join(" · ")}
                  </em>
                )}
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
        装备同一部位只能一件，加成用于解锁门槛；成绩仍由真正答对的题数决定。道具与银两来自读书、委托与试炼。
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
        <span>分 · {run.grade}</span>
      </div>
      <h2>
        {run.definition.kind === "exam"
          ? run.score >= run.definition.passScore
            ? "红榜有名，功名初成"
            : "榜上无名，收卷再读"
          : run.score >= run.definition.passScore
          ? "有所获，亦有所长"
          : "今日未竟，来日再试"}
      </h2>
      <blockquote>{run.response}</blockquote>
      <p>
        本轮答对 {run.correct} / {run.definition.rounds} 题 · 最高成绩{" "}
        {game.adventure!.best[run.definition.id]} 分
      </p>
      <div className="loot-list">
        {run.rewards.length ? (
          run.rewards.map((line, i) => <span key={i}>{line}</span>)
        ) : (
          <span>保留已积累的学识，疑处留待重审</span>
        )}
      </div>
      {game.attempt?.result && (
        <details>
          <summary>回看最后一页解析</summary>
          <p>{game.attempt.result.explanation}</p>
        </details>
      )}
      <button className="gold-button" disabled={busy} onClick={finish}>
        收好所得，回到青溪 →
      </button>
      <small>奖励已自动入账，无需重复领取。</small>
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
  const exam = exams[0], record = game.adventure!.exams[exam.id];
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
        {record.status !== "passed" ? "主线任务" : "支线任务"}
        <span>{record.status !== "passed" ? "科举" : "游历"}</span>
      </div>
      {record.status !== "passed" ? (
        <>
          <h2>{record.status === "unregistered" ? "报名青溪县试" : "入号应试"}</h2>
          <p>{exam.dialogues[record.status]}</p>
          <div className="goal-treasure">榜</div>
          <p>十题定榜 · 全对取中 · 身份与府城路线待解锁</p>
          <button className="main-quest-button" onClick={openExam}>
            <small>主线重要剧情</small>
            查看县试进度 →
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

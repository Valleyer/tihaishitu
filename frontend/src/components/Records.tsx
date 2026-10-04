/**
 * 辅助记录视图：故人、札记、旧案、修业统计。
 * 旧案字母依此处展示的历史选项顺序重新计算，并附选项文字，避免误读洗牌前的答案。
 */
import type { Game } from "../domain/types";
import { displayAnswer } from "../engine/OptionShuffler";
import { favorabilityLevel, gameDesign } from "../content";
import { statistics } from "../engine/StatisticsSystem";
import { isLearningMastered } from "../engine/SpacedRepetitionEngine";
import { Portrait } from "./Portrait";
import { RichText } from "./RichText";
export function People({ game }: { game: Game }) {
  return (
    <div className="people-list">
      {game.npcs.map((npc) => (
        <article
          className={"person-card " + (!npc.met ? "unknown" : "")}
          key={npc.id}
        >
          <Portrait variant={npc.id} />
          <div>
            <small>{npc.met ? npc.role : "未曾谋面"}</small>
            <h3>{npc.met ? npc.name : "前路之人"}</h3>
            <p>
              {npc.met ? npc.description : "山水有相逢，来日或有一面之缘。"}
            </p>
            {npc.met && (
              <>
                <div className="relation-line">
                  <span>好感度</span>
                  <meter min={0} max={100} value={npc.favorability} />
                  <b>{npc.favorability}</b>
                </div>
                <small>{favorabilityLevel(npc.favorability)}</small>
              </>
            )}
          </div>
        </article>
      ))}
    </div>
  );
}
export function Journal({ game }: { game: Game }) {
  return (
    <div className="timeline">
      {[...game.journal]
        .reverse()
        .slice(0, 150)
        .map((entry) => (
          <article key={entry.id} className={entry.kind}>
            <small>
              第{" "}
              {Math.floor(entry.day / gameDesign.calendar[game.config.pace]) +
                1}{" "}
              日 ·{" "}
              {entry.kind === "choice"
                ? "际遇"
                : entry.kind === "milestone"
                  ? "人生节点"
                  : "札记"}
            </small>
            <h3>{entry.title}</h3>
            <p>{entry.text}</p>
          </article>
        ))}
    </div>
  );
}
export function Reviews({
  game,
  start,
  busy,
}: {
  game: Game;
  start: () => void;
  busy: boolean;
}) {
  const mistakes = Object.entries(game.learning)
    .filter(([, r]) => r.wrong > 0 && !isLearningMastered(r))
    .sort((a, b) => b[1].wrong - a[1].wrong);
  return (
    <>
      <div className="prologue">
        <p>“旧错不必讳言。再审一遍，便少一分含混。”</p>
        <button
          className="gold-button"
          disabled={busy || !mistakes.length}
          onClick={start}
        >
          回到案头，开启旧案重审 →
        </button>
        <small>
          先完成当前课卷，再从所选文集的错题中抽取；关闭“只重审旧案”即可恢复常规修习。
        </small>
      </div>
      {!mistakes.length ? (
        <div className="empty-state">
          <span>卷</span>
          <h3>案头清明</h3>
          <p>日后的疑处会留在这里。答错不会断送前程。</p>
        </div>
      ) : (
        mistakes.map(([id, record]) => {
          const question = game.records.find(
            (r) => r.question.id === id,
          )?.question;
          if (!question) return null;
          return (
            <article className="review-entry" key={id}>
              <small>
                {question.subject} · 待重审
              </small>
              <RichText className="review-question">{question.question}</RichText>
              <details>
                <summary>展开旧卷与解析</summary>
                <p>
                  你的旧答：
                  {record.wrongAnswers
                    .map((answer) => displayAnswer(answer, question.options))
                    .join(" / ")}
                </p>
                <p>
                  标准答案：{displayAnswer(question.answer, question.options)}
                </p>
                <RichText>{question.explanation}</RichText>
              </details>
              <div className="hint">
                已重审 {record.reviewCount} 次 · 最近修习{" "}
                {new Date(record.lastAt).toLocaleDateString()} ·{" "}
                {Math.max(0, record.dueAt - game.records.length)}{" "}
                次课业后进入优先复习
              </div>
            </article>
          );
        })
      )}
    </>
  );
}
export function Statistics({ game }: { game: Game }) {
  const stats = statistics(game),
    max = Math.max(1, ...stats.days.map((d) => d.count)),
    recent = stats.days.reduce((total, day) => total + day.count, 0);
  return (
    <>
      <div className="stat-grid">
        {[
          ["累计课业", stats.total],
          ["今日修习", stats.today],
          ["近七日修习", recent],
          ["连续修习", stats.consecutive + " 天"],
        ].map(([label, value]) => (
          <div key={label}>
            <small>{label}</small>
            <strong>{value}</strong>
          </div>
        ))}
      </div>
      <h3 className="section-title">近七日 · 温书留痕</h3>
      <div className="week-chart">
        {stats.days.map((day) => (
          <div key={day.key}>
            <b>{day.count}</b>
            <div className="bar-track">
              <i style={{ height: (day.count / max) * 100 + "%" }} />
            </div>
            <span>{day.label}</span>
          </div>
        ))}
      </div>
      <h3 className="section-title">各科修习</h3>
      {Object.entries(stats.subjects).length ? (
        Object.entries(stats.subjects).map(([name, value]) => (
          <div className="subject-row" key={name}>
            <span>{name}</span>
            <b>已修习 {value.total} 题</b>
          </div>
        ))
      ) : (
        <p className="hint">第一份答卷之后，这里便会留下痕迹。</p>
      )}
      <h3 className="section-title">修习足迹</h3>
      {Object.entries(stats.chapters)
        .sort((a, b) => b[1].total - a[1].total)
        .map(([name, value]) => (
          <div className="subject-row" key={name}>
            <span>{name}</span>
            <b>已修习 {value.total} 题</b>
          </div>
        ))}
    </>
  );
}

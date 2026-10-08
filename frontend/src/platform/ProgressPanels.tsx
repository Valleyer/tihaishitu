import { progressBandLabels, recentContactLabel } from "./progressView";
import type { ActivityDaily, ActivityMetrics, RecentContact } from "./api";

/**
 * PR7 进度页的指标投影与活动趋势图。
 *
 * <p>本文件刻意保持两个「旧版原样」的契约：</p>
 * <ul>
 *   <li>六项指标只投影 `label` + `value`，不携带单位或说明文字；</li>
 *   <li>{@link ActivityBarChart} 是旧 `/statistics` 页的原始单系列柱状图组件（DOM 结构与
 *       高度换算算法均沿用），本轮只更换标题、单位与数据来源。</li>
 * </ul>
 */

export interface MetricSpec {
  label: string;
  value: number;
}

/** 六项核心指标的唯一定义处；顺序即产品确认的展示顺序，只含数字与名称。 */
export function progressMetrics(metrics: ActivityMetrics): MetricSpec[] {
  return [
    { label: "活跃学习日", value: metrics.activeStudyDays7d },
    { label: "今日答题", value: metrics.todayEffectiveAttempts },
    { label: "当前范围知识点", value: metrics.totalKnowledgePoints },
    { label: "接触知识点", value: metrics.touchedKnowledgePoints },
    { label: "累计答题", value: metrics.totalEffectiveAttempts },
    { label: "累计正确", value: metrics.totalCorrectAttempts },
  ];
}

/**
 * 旧版活动趋势图（原 `/statistics` 的 `ActivityBarChart`，单系列柱体）。
 *
 * <p>与 PR7 之前完全一致：同样的 DOM 结构、同样的 `ceiling` 取整与百分比柱高算法、同样的
 * 7/30/90 日期标签密度（`every = 1 / 5 / 14`）、同样的空态位置。改动仅限标题、单位与传入的
 * 真实数据序列。</p>
 */
export function ActivityBarChart({ title, days, values, unit }: {
  title: string; days: number; values: { date: string; value: number }[]; unit: string;
}) {
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

/** 两张趋势图共用的数据投影：每日有效答题次数 / 每日接触知识点数。 */
export function trendSeries(daily: ActivityDaily[]) {
  return {
    attempts: daily.map(day => ({ date: day.date, value: day.effectiveAttempts })),
    knowledgePoints: daily.map(day => ({ date: day.date, value: day.distinctKnowledgePoints })),
  };
}

/**
 * 首页「学习足迹」条目的状态展示（进度页本轮已下线该模块）。
 *
 * <p>判据来自最近一次 Attempt 的真实状态，不是 {@code evidenceCount}：已有真实评分但暂无
 * Mastery Evidence 时显示「已作答 · 暂无掌握证据」，绝不说成「仅查看答案」。</p>
 */
export function RecentContactState({ contact, timestamp }: { contact: RecentContact; timestamp: string }) {
  const label = recentContactLabel(contact);
  if (label.kind === "mastery") {
    return <><span className={`mastery-band ${contact.band}`}>{progressBandLabels[contact.band]}</span>
      <small>{Math.round(contact.effectiveMastery)}% · {timestamp}</small></>;
  }
  return <><span className={`mastery-band ${label.kind === "reveal_only" ? "unstarted" : "learning"}`}>{label.text}</span>
    <small>{timestamp}</small></>;
}

import type { ActivityDaily, ActivityMetrics } from "./api";

/**
 * PR7 进度统计 V3：六个核心指标与近 7 日趋势的展示组件。
 *
 * <p>与 `PlatformApp` 分离，避免把统计口径与视觉逻辑堆在路由文件里；这里不做任何
 * 数值推导，只渲染后端 `activity` 已经算好的统一事实。</p>
 */

export interface MetricSpec {
  label: string;
  value: number;
  hint: string;
  unit?: string;
}

/** 六个核心指标的唯一定义处；顺序即产品确认的展示顺序。 */
export function progressMetrics(metrics: ActivityMetrics): MetricSpec[] {
  return [
    { label: "活跃学习日", value: metrics.activeStudyDays7d, hint: "近 7 天有答题记录的天数", unit: "天" },
    { label: "今日答题", value: metrics.todayEffectiveAttempts, hint: "今天发生的有效答题", unit: "次" },
    { label: "当前范围知识点", value: metrics.totalKnowledgePoints, hint: "当前学习范围里可学习的知识点", unit: "个" },
    { label: "接触知识点", value: metrics.touchedKnowledgePoints, hint: "全历史有效答过的知识点", unit: "个" },
    { label: "累计答题", value: metrics.totalEffectiveAttempts, hint: "当前范围全历史有效答题", unit: "次" },
    { label: "累计正确", value: metrics.totalCorrectAttempts, hint: "当前范围全历史答对", unit: "次" },
  ];
}

export function isActivityEmpty(daily: ActivityDaily[]): boolean {
  return daily.every(day => day.effectiveAttempts === 0 && day.distinctKnowledgePoints === 0);
}

/**
 * 近 7 天趋势：每日有效答题数与每日接触知识点数共用一条上海业务日轴。
 *
 * <p>零值日期保留整列，只用真实空态说明没有任何活动，不用虚构记录补位。柱高按显式绘图区
 * 高度换算成像素而不是百分比：网格 / flex 子项里的百分比高度在 `1fr` 轨道中解析不稳定，
 * 容易把柱子压成 0。</p>
 */
export const ACTIVITY_PLOT_HEIGHT = 200;

export function ActivityChart({ daily }: { daily: ActivityDaily[] }) {
  const max = Math.max(1, ...daily.flatMap(day => [day.effectiveAttempts, day.distinctKnowledgePoints]));
  const empty = isActivityEmpty(daily);
  // 空态不编造刻度：只有一个 0 基线，不给「没有记录」配一个看起来很忙的数轴。
  const ceiling = empty ? 1 : Math.max(1, Math.ceil(max / 5) * 5);
  const ticks = empty ? [0] : [ceiling, Math.round(ceiling / 2), 0];
  const barHeight = (value: number) => Math.round(value / ceiling * ACTIVITY_PLOT_HEIGHT);
  // 零值不画柱子；有值至少 3px，避免 1 次答题看不见。
  const barWidth = (value: number) => value === 0 ? 0 : Math.max(3, Math.round(value / max * 18));

  return <section className="hub-panel progress-activity">
    <header className="activity-chart-heading">
      <h2>近 7 天趋势</h2>
      <span>按上海业务日 · 从早到晚</span>
    </header>
    <div className="activity-legend">
      <span><i className="legend-bar attempts" aria-hidden="true" />每日有效答题</span>
      <span><i className="legend-bar points" aria-hidden="true" />每日接触知识点</span>
    </div>
    <div className="activity-chart-body" style={{ gridTemplateRows: `${ACTIVITY_PLOT_HEIGHT}px 24px` }}>
      <div className="chart-y-title">数量</div>
      <div className={empty ? "chart-y-axis single" : "chart-y-axis"} aria-hidden="true">
        {ticks.map(tick => <span key={tick}>{tick}</span>)}
      </div>
      <div className="chart-plot" style={{ gridTemplateRows: `${ACTIVITY_PLOT_HEIGHT}px 24px` }}>
        <div className="chart-bars">
          {daily.map(day => <div className="chart-column" key={day.date}
            title={`${day.date}：有效答题 ${day.effectiveAttempts} 次，接触知识点 ${day.distinctKnowledgePoints} 个`}>
            <div className="chart-bar-stack">
              <i className="chart-bar attempts" style={{ height: `${barHeight(day.effectiveAttempts)}px`, width: `${barWidth(day.effectiveAttempts)}px` }} />
              <i className="chart-bar points" style={{ height: `${barHeight(day.distinctKnowledgePoints)}px`, width: `${barWidth(day.distinctKnowledgePoints)}px` }} />
            </div>
            <span className="chart-date">{day.date.slice(5)}</span>
          </div>)}
        </div>
      </div>
    </div>
    {empty && <p className="chart-empty">这 7 天还没有有效答题记录。开始一次练习后，趋势会显示在这里。</p>}
  </section>;
}

/** 答题结果分布。四类之和与「累计答题」一致，仅查看答案不并入错误。 */
export function OutcomeDistribution({ outcomes }: { outcomes: { correct: number; partial: number; wrong: number; revealedOnly: number } }) {
  const total = outcomes.correct + outcomes.partial + outcomes.wrong + outcomes.revealedOnly;
  const rows: [string, number, string][] = [
    ["正确", outcomes.correct, "correct"],
    ["部分正确", outcomes.partial, "partial"],
    ["错误", outcomes.wrong, "wrong"],
    ["仅查看答案", outcomes.revealedOnly, "revealed"],
  ];
  return <section className="hub-panel progress-outcomes">
    <header className="section-heading"><h2>答题结果分布</h2><span>累计 {total} 次有效答题</span></header>
    <div className="outcome-rows">
      {rows.map(([label, value, tone]) => <div className="horizontal-stat" key={label}>
        <span>{label}</span>
        <i><b className={tone} style={{ width: `${total === 0 ? 0 : value / total * 100}%` }} /></i>
        <strong>{value}</strong>
      </div>)}
    </div>
    <p className="outcome-note">「仅查看答案」是尚未自评的参考解析阅读，不算错误，也不算掌握。</p>
  </section>;
}

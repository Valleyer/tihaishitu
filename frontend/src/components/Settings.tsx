/**
 * 当前人生的题库与科目权重。提交配置不重置进度，新的选题规则在下一页课卷使用。
 */
import { useState } from "react";
import type { Bank, Game } from "../domain/types";
export function Settings({
  game,
  banks,
  busy,
  save,
}: {
  game: Game;
  banks: Bank[];
  busy: boolean;
  save: (ids: string[], weights: Record<string, number>) => void;
}) {
  const [ids, setIds] = useState(game.config.bankIds),
    [weights, setWeights] = useState(game.config.weights);
  const subjects = [
    ...new Set(
      banks
        .filter((b) => ids.includes(b.id))
        .flatMap((b) => b.questions.map((q) => q.subject)),
    ),
  ];
  return (
    <>
      <p className="hint">
        更换随身书卷不会重置人物、剧情或学习记录。新的配置从下一页生效。
      </p>
      <div className="bank-choices">
        {banks.map((bank) => (
          <label key={bank.id}>
            <input
              type="checkbox"
              disabled={!bank.enabled}
              checked={ids.includes(bank.id)}
              onChange={(e) =>
                setIds(
                  e.target.checked
                    ? [...ids, bank.id]
                    : ids.filter((id) => id !== bank.id),
                )
              }
            />
            <span>
              {bank.name}
              <small>
                {bank.enabled
                  ? bank.questions.length + " 题"
                  : "已停用，请先在藏书阁启用"}
              </small>
            </span>
          </label>
        ))}
      </div>
      <h3 className="section-title">修习比重</h3>
      <div className="weights">
        {subjects.map((subject) => (
          <label key={subject}>
            {subject}
            <input
              type="number"
              min={0}
              max={100}
              value={weights[subject] ?? 1}
              onChange={(e) =>
                setWeights({ ...weights, [subject]: Number(e.target.value) })
              }
            />
          </label>
        ))}
      </div>
      <button
        className="gold-button"
        disabled={busy || !ids.length}
        onClick={() => save(ids, weights)}
      >
        收好行囊
      </button>
    </>
  );
}

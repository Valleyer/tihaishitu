/** 新人生只留姓名、性别与文集；刷题数量完全由玩家当下决定。 */
import { useState } from "react";
import type { Bank, NewGame } from "../domain/types";
import { gameDesign } from "../content";
export function NewGameForm({
  banks,
  start,
  busy,
}: {
  banks: Bank[];
  start: (config: NewGame) => void;
  busy: boolean;
}) {
  const [config, setConfig] = useState<NewGame>({
    name: "折叶",
    gender: "男",
    origin: gameDesign.origins[0].name,
    bankIds: banks.filter((b) => b.enabled).map((b) => b.id),
    weights: { ...gameDesign.defaultWeights },
    pace: "normal",
    difficulty: "gentle",
  });
  const subjects = [
    ...new Set(
      banks
        .filter((b) => config.bankIds.includes(b.id))
        .flatMap((b) => b.questions.map((q) => q.subject)),
    ),
  ];
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        start(config);
      }}
      className="new-game-form"
    >
      <div className="form-grid">
        <label>
          姓名
          <input
            required
            maxLength={12}
            value={config.name}
            onChange={(e) => setConfig({ ...config, name: e.target.value })}
          />
        </label>
        <label>
          性别
          <select
            value={config.gender}
            onChange={(e) => setConfig({ ...config, gender: e.target.value })}
          >
            <option>男</option>
            <option>女</option>
            <option>不设定</option>
          </select>
        </label>
      </div>
      <h3 className="section-title">
        随身书卷<small>只决定抽题范围，想刷多少由你随时决定</small>
      </h3>
          <div className="bank-choices">
            {banks
              .filter((b) => b.enabled)
              .map((bank) => (
                <label key={bank.id}>
                  <input
                    type="checkbox"
                    checked={config.bankIds.includes(bank.id)}
                    onChange={(e) =>
                      setConfig({
                        ...config,
                        bankIds: e.target.checked
                          ? [...config.bankIds, bank.id]
                          : config.bankIds.filter((id) => id !== bank.id),
                      })
                    }
                  />
                  <span>
                    {bank.name}
                    <small>
                      {bank.questions.filter((q) => q.enabled).length} 题 ·{" "}
                      {bank.description}
                    </small>
                  </span>
                </label>
              ))}
          </div>
          <div className="weights">
            {subjects.map((subject) => (
              <label key={subject}>
                {subject}
                <input
                  type="number"
                  min={0}
                  max={100}
                  value={config.weights[subject] ?? 1}
                  onChange={(e) =>
                    setConfig({
                      ...config,
                      weights: {
                        ...config.weights,
                        [subject]: Number(e.target.value),
                      },
                    })
                  }
                />
              </label>
            ))}
          </div>
          <p className="hint">
            数字只表示科目抽取比重；0 为暂不修习。系统不设置每日题量或刷题目标，只记录实际学习。
          </p>
      <button
        className="gold-button full"
        disabled={busy || !config.name.trim() || !config.bankIds.length}
      >
        落下姓名，启程入世 →
      </button>
    </form>
  );
}

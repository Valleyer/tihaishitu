/**
 * 新人生分两步填写，减少小屏一次展示的表单量；出身和科目默认权重来自 game.json。
 */
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
  const [step, setStep] = useState(0);
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
        if (step === 0) setStep(1);
        else start(config);
      }}
      className="new-game-form"
    >
      <div className="form-steps">
        <span className={step === 0 ? "active" : ""}>一 · 留名</span>
        <i />
        <span className={step === 1 ? "active" : ""}>二 · 选卷</span>
      </div>
      {step === 0 ? (
        <>
          <div className="prologue compact-prologue">
            <span>
              {gameDesign.era}
              {gameDesign.startYear}年 · 春
            </span>
            <p>{gameDesign.prologue.at(-1)}</p>
          </div>
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
                onChange={(e) =>
                  setConfig({ ...config, gender: e.target.value })
                }
              >
                <option>男</option>
                <option>女</option>
                <option>不设定</option>
              </select>
            </label>
            <label>
              出身
              <select
                value={config.origin}
                onChange={(e) =>
                  setConfig({ ...config, origin: e.target.value })
                }
              >
                {gameDesign.origins.map((origin) => (
                  <option key={origin.name}>{origin.name}</option>
                ))}
              </select>
            </label>
            <label>
              人生节奏
              <select
                value={config.pace}
                onChange={(e) =>
                  setConfig({
                    ...config,
                    pace: e.target.value as NewGame["pace"],
                  })
                }
              >
                <option value="normal">
                  从容 · 每 {gameDesign.calendar.normal} 题一日
                </option>
                <option value="slow">
                  细读 · 每 {gameDesign.calendar.slow} 题一日
                </option>
              </select>
            </label>
            <label>
              处世难度
              <select
                value={config.difficulty}
                onChange={(e) =>
                  setConfig({
                    ...config,
                    difficulty: e.target.value as NewGame["difficulty"],
                  })
                }
              >
                <option value="gentle">宽和 · 失误不减信任</option>
                <option value="standard">持重 · 重复失误轻减信任</option>
              </select>
            </label>
          </div>
          <p className="hint">
            无功名，无官职，无显赫门第。你的一切，都从这一卷开始。
          </p>
          <button
            className="gold-button full"
            disabled={busy || !config.name.trim()}
          >
            收好姓名，挑选书卷 →
          </button>
        </>
      ) : (
        <>
          <h3 className="section-title">
            随身书卷<small>选择这段人生要修习的内容</small>
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
            数字为科目抽取比重；0 为暂不修习。题库可在入世后随时更换。
          </p>
          <div className="toolbar">
            <button type="button" onClick={() => setStep(0)}>
              ← 返回留名
            </button>
            <button
              className="gold-button"
              disabled={busy || !config.bankIds.length}
            >
              落下姓名，启程入世 →
            </button>
          </div>
        </>
      )}
    </form>
  );
}

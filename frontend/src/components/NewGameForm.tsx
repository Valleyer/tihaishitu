/** 新人生只留姓名与性别；可用文集由题库目录自动接入。 */
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
  const availableBankIds = banks
    .filter(
      (bank) =>
        bank.enabled &&
        bank.weight > 0 &&
        bank.questions.some((question) => question.enabled),
    )
    .map((bank) => bank.id);
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        start({ ...config, bankIds: availableBankIds });
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
      <button
        className="gold-button full"
        disabled={busy || !config.name.trim()}
      >
        落下姓名，启程入世 →
      </button>
    </form>
  );
}

/**
 * 本地立绘图集：按 portraits.json 的列号裁切；多个角色可以引用同一张立绘。
 * 人物身份、关系取自存档；立绘映射取配置，换图无需修改剧情代码。
 */
import { characterDesign, portraitDesign } from "../content";
export function Portrait({
  variant = "player",
  female = false,
}: {
  variant?: string;
  female?: boolean;
}) {
  const key =
    variant === "player"
      ? female
        ? "playerFemale"
        : "playerMale"
      : characterDesign.find((n) => n.id === variant)?.portrait || variant;
  const entry =
    portraitDesign.portraits[key as keyof typeof portraitDesign.portraits] ||
    portraitDesign.portraits.playerMale;
  return (
    <div
      className={"portrait portrait-" + variant}
      role="img"
      aria-label={entry.label}
      style={{
        backgroundImage: "url(" + portraitDesign.sheet + ")",
        backgroundSize: portraitDesign.columns * 100 + "% 100%",
        backgroundPosition:
          (entry.column / Math.max(1, portraitDesign.columns - 1)) * 100 +
          "% center",
      }}
    />
  );
}

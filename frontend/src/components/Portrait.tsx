/**
 * 本地独立头像：人物只保存 portrait 键，实际图片与取景位置均由 portraits.json 配置。
 * 头像在人物卡、对话框和主角侧栏中复用；替换美术无需修改组件代码。
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
        backgroundImage: "url(" + entry.src + ")",
        backgroundSize: "cover",
        backgroundPosition: entry.position,
      }}
    />
  );
}

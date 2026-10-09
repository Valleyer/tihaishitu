/**
 * 全屏必须由玩家点击按钮触发。监听 fullscreenchange 以同步 Esc 退出后的按钮状态。
 */
import { useEffect, useState } from "react";
export function DisplaySettings() {
  const [fullscreen, setFullscreen] = useState(!!document.fullscreenElement),
    [error, setError] = useState("");
  useEffect(() => {
    const update = () => setFullscreen(!!document.fullscreenElement);
    document.addEventListener("fullscreenchange", update);
    return () => document.removeEventListener("fullscreenchange", update);
  }, []);
  async function toggle() {
    setError("");
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else await document.documentElement.requestFullscreen();
    } catch {
      setError(
        "当前窗口未允许全屏，请在浏览器中打开游戏后重试，或使用浏览器全屏快捷键。",
      );
    }
  }
  return (
    <div className="display-settings">
      <div>
        <h3>沉浸全屏</h3>
        <p>铺开山河卷，让题目占据眼前。按 Esc 可退出全屏。</p>
        <button className="gold-button" onClick={() => void toggle()}>
          {fullscreen ? "退出全屏" : "进入全屏"}
        </button>
      </div>
      {error && (
        <p className="error-banner" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

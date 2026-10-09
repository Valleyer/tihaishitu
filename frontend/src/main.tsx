import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import ManagementApp from "./manage/ManagementApp.tsx";
import PlatformApp from "./platform/PlatformApp.tsx";
import { MobileLandscapeShell } from "./components/MobileLandscapeShell.tsx";

const RootApp = window.location.pathname.startsWith("/manage")
  ? ManagementApp
  : PlatformApp;

/**
 * MobileLandscapeShell 始终包在最外层且 DOM 层级稳定：
 * 手机 portrait 登录后由它把整棵已登录应用旋转成横屏画布，
 * 认证页、真实 landscape 与桌面都保持原样。
 */
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <MobileLandscapeShell>
      <RootApp />
    </MobileLandscapeShell>
  </StrictMode>,
);

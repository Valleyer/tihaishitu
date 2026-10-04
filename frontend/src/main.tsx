import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import ManagementApp from "./manage/ManagementApp.tsx";
import PlatformApp from "./platform/PlatformApp.tsx";

const RootApp = window.location.pathname.startsWith("/manage")
  ? ManagementApp
  : PlatformApp;

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RootApp />
  </StrictMode>,
);

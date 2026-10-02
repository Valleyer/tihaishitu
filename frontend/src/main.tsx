import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./index.css";
import App from "./App.tsx";
import ManagementApp from "./manage/ManagementApp.tsx";

const RootApp = window.location.pathname.startsWith("/manage")
  ? ManagementApp
  : App;

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RootApp />
  </StrictMode>,
);

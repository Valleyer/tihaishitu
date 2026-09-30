import type { GameApi } from "../domain/types";
import { httpApi } from "./http";
import { createLocalApi } from "./local/store";
const mode = import.meta.env.VITE_API_MODE || "local";
if (!["local", "http"].includes(mode))
  throw new Error("VITE_API_MODE 只能是 local 或 http");
export const api: GameApi =
  mode === "http"
    ? httpApi
    : createLocalApi({
        getItem: (key) => localStorage.getItem(key),
        setItem: (key, value) => localStorage.setItem(key, value),
      });

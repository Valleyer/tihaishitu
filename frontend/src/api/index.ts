/**
 * 在 local 与 http 实现之间切换。界面不感知存储方式，后续对接 Java 只需保持 GameApi 契约。
 */
import type { GameApi } from "../domain/types";
import { httpApi } from "./http";
import { createLocalApi } from "./local/store";
const mode = import.meta.env.VITE_API_MODE || "http";
if (!["local", "http"].includes(mode))
  throw new Error("VITE_API_MODE 只能是 local 或 http");
export const api: GameApi =
  mode === "http"
    ? httpApi
    : createLocalApi({
        getItem: (key) => localStorage.getItem(key),
        setItem: (key, value) => localStorage.setItem(key, value),
      });

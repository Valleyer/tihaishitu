import { exams } from "../content";
import type { Game } from "../domain/types";

export interface WorldPresentation {
  cover?: string;
  tags: string[];
  accent?: string;
}

const DEFAULT_PRESENTATION: WorldPresentation = {
  tags: [],
  accent: "#667085",
};

const WORLD_PRESENTATION: Record<string, WorldPresentation> = {
  "ancient-official": {
    cover: "/art/academy.png",
    tags: ["科举", "人生", "成长"],
    accent: "#8a6335",
  },
};

export const worldPresentation = (worldId: string): WorldPresentation =>
  WORLD_PRESENTATION[worldId] ?? DEFAULT_PRESENTATION;

/** 功名是 Exam 主线状态的配置驱动投影，与 player.title 叙事身份分开。 */
export const meritLabel = (game: Game): string =>
  [...exams]
    .reverse()
    .find((exam) => game.adventure?.exams[exam.id]?.status === "passed")
    ?.meritTitle || "尚无功名";

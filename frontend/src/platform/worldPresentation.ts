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

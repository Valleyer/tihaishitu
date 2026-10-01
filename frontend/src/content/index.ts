/**
 * 可配置内容的统一入口。JSON 不能写注释，字段说明与示例见 docs/configuration-guide.md。
 * id 是关联键，修改文字不必改 id；修改章节顺序或删除人物后建议新开存档。
 * 内容只在这里导入；界面与规则引擎统一引用，避免多处维护同一套设定。
 */
import gameData from "./game.json";
import chapterData from "./chapters.json";
import characterData from "./characters.json";
import sceneData from "./scenes.json";
import eventData from "./events.json";
import mapData from "./maps.json";
import portraitData from "./portraits.json";
import bankData from "./question-banks.json";
import activityData from "./activities.json";
import companionData from "./companions.json";
import itemData from "./items.json";
import adventureData from "./adventure.json";
import type {
  Activity,
  Companion,
  Item,
  WorldLocation,
} from "../domain/adventure";
export const activities = activityData as unknown as Activity[];
export const companions = companionData as unknown as Companion[];
export const items = itemData as unknown as Item[];
export const adventureDesign = adventureData;
import type { Bank, ChoiceEvent, Npc } from "../domain/types";
export const gameDesign = gameData;
export const chapterDesign = chapterData;
export const characterDesign = characterData as (Npc & { portrait: string })[];
export const sceneDesign = sceneData;
export interface EventEffects {
  knowledge?: number;
  coins?: number;
  reputation?: number;
  trust?: Record<string, number>;
  affinity?: Record<string, number>;
  flags?: string[];
}
export const eventDesign = eventData as (Omit<ChoiceEvent, "options"> & {
  at: number;
  speaker: string;
  npcId: string;
  options: (ChoiceEvent["options"][number] & { effects: EventEffects })[];
})[];
export const mapDesign = mapData as unknown as { locations: WorldLocation[] };
export const portraitDesign = portraitData;
export const bankDesign = bankData as unknown as Bank[];
export const locationFor = (chapter: number) =>
  mapDesign.locations.find(
    (location) => location.id === chapterDesign[chapter].locationId,
  ) || mapDesign.locations[0];

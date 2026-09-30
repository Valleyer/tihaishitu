/**
 * 地图地点、坐标和背景来自 maps.json；章节通过 locationId 关联地点。
 * 此版地图用于展示已到达与尚未到达地点，推进仍由课业和剧情触发。
 */
import { chapterDesign, mapDesign } from "../content";
import type { Game } from "../domain/types";
export function WorldMap({ game }: { game: Game }) {
  return (
    <>
      <p className="hint">
        地图用于回看旅途，不需要跑图才能作答。随着章节推进，新的地点自然开放。
      </p>
      <div className="county-map">
        {mapDesign.locations.map((location, i) => (
          <div
            className={
              "map-location " +
              (i === game.chapter
                ? "active"
                : i > game.chapter
                  ? "future"
                  : "visited")
            }
            key={location.id}
            style={{ left: location.x + "%", top: location.y + "%" }}
          >
            <i />
            <b>{location.name.split(" · ")[1]}</b>
            <small>
              {i === game.chapter
                ? "身在此处"
                : i > game.chapter
                  ? "尚未抵达"
                  : chapterDesign[i]?.title}
            </small>
          </div>
        ))}
      </div>
      <p className="map-caption">
        {
          mapDesign.locations.find(
            (l) => l.id === chapterDesign[game.chapter].locationId,
          )?.description
        }
      </p>
    </>
  );
}

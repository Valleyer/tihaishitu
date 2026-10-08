/** 可点击地图：先查看地点、人物和门槛，再通过 API 移动，锁定地点也可查看解锁目标。 */
import { useState } from "react";
import { activities, mapDesign } from "../content";
import type { Game } from "../domain/types";
import { requirementIssues } from "../engine/AdventureEngine";
export function WorldMap({
  game,
  busy,
  travel,
  study,
}: {
  game: Game;
  busy: boolean;
  travel: (id: string) => void;
  study: () => void;
}) {
  const currentLocation =
      mapDesign.locations.find((l) => l.id === game.adventure!.locationId) ||
      mapDesign.locations[0],
    [regionId, setRegionId] = useState(currentLocation.regionId),
    region =
      mapDesign.regions.find((item) => item.id === regionId) ||
      mapDesign.regions[0],
    regionLocations = mapDesign.locations.filter(
      (item) => item.regionId === region.id,
    );
  const [selected, setSelected] = useState(currentLocation.id);
  const location =
      regionLocations.find((l) => l.id === selected) || regionLocations[0],
    issues = requirementIssues(game, location.requirements),
    state = game.adventure!;
  return (
    <>
      <p className="hint">
        山河可行，故人可访。随着学识、声望与行旅推进，远处的门会逐一打开。
      </p>
      <nav className="map-region-tabs" aria-label="大地图选择">
        {mapDesign.regions.map((item) => {
          const locked = requirementIssues(game, item.requirements).length > 0;
          return (
            <button
              key={item.id}
              className={item.id === region.id ? "active" : ""}
              disabled={locked}
              onClick={() => {
                setRegionId(item.id);
                setSelected(
                  mapDesign.locations.find((loc) => loc.regionId === item.id)?.id ||
                    selected,
                );
              }}
            >
              {item.name}{locked ? " · 待解锁" : ""}
            </button>
          );
        })}
      </nav>
      <p className="map-region-description">{region.description}</p>
      <div
        className="county-map interactive-map"
        style={{
          backgroundImage: `linear-gradient(#233d2999, #182f2bcc), url(${region.background})`,
          backgroundPosition: region.position,
        }}
      >
        {regionLocations.map((loc) => (
          <button
            className={
              "map-location " +
              (loc.id === selected ? "active" : "") +
              (requirementIssues(game, loc.requirements).length
                ? " future"
                : "")
            }
            key={loc.id}
            style={{ left: loc.x + "%", top: loc.y + "%" }}
            onClick={() => setSelected(loc.id)}
          >
            <i />
            <b>{loc.name.split(" · ").at(-1)}</b>
            <small>
              {loc.id === state.locationId
                ? "身在此处"
                : requirementIssues(game, loc.requirements).length
                  ? "待解锁"
                  : state.visited.includes(loc.id)
                    ? "曾来过"
                    : "可前往"}
            </small>
          </button>
        ))}
      </div>
      <section className="map-destination">
        <div>
          <small>此地风物</small>
          <h3>{location.name}</h3>
          <p>{location.ambience}</p>
          <small>
            {activities
              .filter((a) => a.locationId === location.id)
              .map((a) => a.name)
              .join(" · ") || "静读与休憩"}
          </small>
        </div>
        <div>
          {issues.length > 0 ? (
            <>
              <p>尚需：{issues.join("；")}</p>
              <button onClick={study}>读书提升学识 →</button>
            </>
          ) : (
            <button
              className="gold-button"
              disabled={busy || !!state.run}
              onClick={() => travel(location.id)}
            >
              {location.id === state.locationId ? "回到此地" : "动身前往"} →
            </button>
          )}
          {state.run && <small>请先结束当前行程再出发</small>}
        </div>
      </section>
    </>
  );
}

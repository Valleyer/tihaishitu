/**
 * 章节入场对话页。点击推进对白，最后经 API 确认章首已读；刷新不会丢掉章节进度。
 */
import { useEffect, useRef, useState } from "react";
import type { Game } from "../domain/types";
import {
  chapterDesign,
  gameDesign,
  locationFor,
  characterDesign,
} from "../content";
import { Portrait } from "./Portrait";
export function ChapterGate({
  game,
  busy,
  enter,
}: {
  game: Game;
  busy: boolean;
  enter: () => void;
}) {
  const chapter = chapterDesign[game.chapter],
    location = locationFor(game.chapter),
    npc = characterDesign.find((n) => n.id === chapter.intro.npcId);
  const [line, setLine] = useState(0),
    button = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    button.current?.focus();
  }, []);
  return (
    <section
      className="chapter-gate"
      style={{ backgroundImage: "url(" + location.background + ")" }}
      aria-label="章节序幕"
    >
      <div className="gate-shade" />
      <div className="gate-content">
        <small>
          {gameDesign.volume} · 第 {String(game.chapter + 1).padStart(2, "0")}{" "}
          章
        </small>
        <h1>{chapter.title}</h1>
        <p className="gate-location">{location.name}</p>
        <div className="gate-dialogue">
          <b>{chapter.intro.speaker}</b>
          <p>{chapter.intro.lines[line]}</p>
          <span>
            {line + 1} / {chapter.intro.lines.length}
          </span>
        </div>
        <div className="gate-progress">
          <span>
            已历练 <b>{game.records.length}</b> 次
          </span>
          <span>
            学识 <b>{game.player.knowledge}</b>
          </span>
          <span>
            身份 <b>{game.player.title}</b>
          </span>
        </div>
        <div className="gate-objective">
          <small>本章所志</small>
          <p>{chapter.goal}</p>
        </div>
        <button
          ref={button}
          className="gold-button"
          disabled={busy}
          onClick={() => {
            if (line < chapter.intro.lines.length - 1) setLine(line + 1);
            else enter();
          }}
        >
          {line < chapter.intro.lines.length - 1
            ? "听下去"
            : chapter.intro.action}
          <span>→</span>
        </button>
      </div>
      <div className="gate-portrait">
        <Portrait variant={npc?.id || "player"} />
      </div>
    </section>
  );
}

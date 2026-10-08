import { describe, expect, it } from "vitest";
import type { Game } from "../domain/types";
import { meritLabel } from "./worldPresentation";

const gameWithExamStatuses = (county: string, prefecture: string) =>
  ({
    adventure: {
      exams: {
        "county-exam": { status: county },
        "prefecture-exam": { status: prefecture },
      },
    },
  }) as unknown as Game;

describe("meritLabel", () => {
  it("derives the highest merit title from configured passed exams", () => {
    expect(meritLabel(gameWithExamStatuses("unregistered", "unregistered"))).toBe("尚无功名");
    expect(meritLabel(gameWithExamStatuses("passed", "unregistered"))).toBe("县试取中");
    expect(meritLabel(gameWithExamStatuses("passed", "passed"))).toBe("府试取中");
  });
});

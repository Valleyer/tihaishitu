import { describe, expect, it } from "vitest";
import { manageLabel } from "./manageLabels";

describe("management Chinese labels", () => {
  it("keeps machine codes stable while presenting Chinese labels", () => {
    expect(manageLabel("questionStatus", "pending_review")).toBe("待审核");
    expect(manageLabel("questionType", "single_choice")).toBe("单选题");
    expect(manageLabel("grading", "auto")).toBe("自动判题");
    expect(manageLabel("knowledgeStatus", "active")).toBe("有效");
    expect(manageLabel("userStatus", "active")).toBe("正常");
    expect(manageLabel("role", "REVIEWER")).toBe("审核员");
  });
});

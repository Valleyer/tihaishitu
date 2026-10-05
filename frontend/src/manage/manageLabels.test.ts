import { describe, expect, it } from "vitest";
import { manageLabel, manageOptions, questionTypeContract } from "./manageLabels";

describe("management Chinese labels", () => {
  it("keeps machine codes stable while presenting Chinese labels", () => {
    expect(manageLabel("questionStatus", "pending_review")).toBe("待审核");
    expect(manageLabel("questionType", "single_choice")).toBe("单选题");
    expect(manageLabel("questionType", "solution")).toBe("综合题");
    expect(manageOptions("questionType").map(option => option.value)).toEqual([
      "single_choice", "multiple_choice", "true_false", "solution",
    ]);
    expect(questionTypeContract("solution")).toEqual({
      presentationType: "self_assessment", gradingMode: "self_assessment",
    });
    expect(manageLabel("grading", "auto")).toBe("自动判题");
    expect(manageLabel("knowledgeStatus", "active")).toBe("有效");
    expect(manageLabel("userStatus", "active")).toBe("正常");
    expect(manageLabel("role", "REVIEWER")).toBe("审核员");
  });
});
